package com.lou.infinitechatagent.agent;

import com.lou.infinitechatagent.agent.dto.AgentAction;
import com.lou.infinitechatagent.agent.dto.AgentActionType;
import com.lou.infinitechatagent.agent.dto.AgentObservation;
import com.lou.infinitechatagent.agent.dto.AgentPlan;
import com.lou.infinitechatagent.agent.dto.AgentRequest;
import com.lou.infinitechatagent.agent.dto.AgentResponse;
import com.lou.infinitechatagent.agent.dto.ReActStep;
import com.lou.infinitechatagent.agent.governance.ToolGovernanceService;
import com.lou.infinitechatagent.agent.governance.dto.ToolGovernanceDecision;
import com.lou.infinitechatagent.agent.planner.LlmAgentPlanner;
import com.lou.infinitechatagent.agent.planner.RuleBasedAgentPlanner;
import com.lou.infinitechatagent.memory.MemoryAgent;
import com.lou.infinitechatagent.memory.dto.MemoryContext;
import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.MemoryTrace;
import com.lou.infinitechatagent.rag.RagQueryService;
import com.lou.infinitechatagent.rag.dto.RagQueryResponse;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class ReActAgentOrchestrator {

    @Resource
    private RagQueryService ragQueryService;

    @Resource
    private ChatModel chatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private MemoryAgent memoryAgent;

    @Resource
    private RuleBasedAgentPlanner ruleBasedAgentPlanner;

    @Resource
    private LlmAgentPlanner llmAgentPlanner;

    @Resource
    private ToolGovernanceService toolGovernanceService;

    @Value("${agent.react.max-output-tokens:500}")
    private int maxOutputTokens;

    @Value("${agent.react.memory-max-messages:20}")
    private int memoryMaxMessages;

    @Value("${agent.react.planner.mode:RULE_BASED}")
    private String plannerMode;

    public AgentResponse chat(AgentRequest request) {
        long start = System.currentTimeMillis();
        String prompt = normalizePrompt(request.getPrompt());
        MemoryTrace memoryTrace = memoryAgent.readContext(request.getUserId(), request.getSessionId(), prompt);
        MemoryContext memoryContext = memoryTrace.getContext();
        AgentPlan plan = plan(prompt);
        AgentAction action = plan.getAction();
        ToolGovernanceDecision governanceDecision = toolGovernanceService.evaluate(
                request.getUserId(),
                request.getSessionId(),
                prompt,
                action,
                request.getConfirmedTools());

        if (!Boolean.TRUE.equals(governanceDecision.getAllowed())) {
            return blockedByGovernance(prompt, plan, governanceDecision, start);
        }

        return switch (action.getType()) {
            case HYBRID_SEARCH -> answerWithRag(request.getUserId(), request.getSessionId(), prompt, plan, governanceDecision, start);
            case CURRENT_TIME -> answerWithCurrentTime(request.getUserId(), request.getSessionId(), prompt, plan, governanceDecision, start);
            case NO_RETRIEVAL_ANSWER -> answerDirectly(request.getUserId(), request.getSessionId(), prompt, plan, memoryContext, governanceDecision, start);
            default -> answerDirectly(request.getUserId(), request.getSessionId(), prompt, plan, memoryContext, governanceDecision, start);
        };
    }

    private AgentPlan plan(String prompt) {
        if ("LLM".equalsIgnoreCase(plannerMode)) {
            return llmAgentPlanner.plan(prompt);
        }
        return ruleBasedAgentPlanner.plan(prompt);
    }

    private AgentResponse answerWithRag(Long userId,
                                        Long sessionId,
                                        String prompt,
                                        AgentPlan plan,
                                        ToolGovernanceDecision governanceDecision,
                                        long start) {
        long actionStart = System.currentTimeMillis();
        RagQueryResponse ragResponse = ragQueryService.chatWithCitations(sessionId, prompt);
        memoryAgent.afterAnswer(userId, sessionId, prompt);
        ReActStep step = ReActStep.builder()
                .step(1)
                .thought(plan.getThought())
                .needRetrieval(plan.getNeedRetrieval())
                .actionReason(plan.getActionReason())
                .confidence(plan.getConfidence())
                .action(plan.getAction())
                .observation(AgentObservation.builder()
                        .success(Boolean.TRUE.equals(ragResponse.getHit()))
                        .summary(String.format("hybrid search retrieved=%s, candidates=%s, citations=%s",
                                ragResponse.getRetrievedCount(),
                                ragResponse.getCandidateCount(),
                                ragResponse.getCitations() == null ? 0 : ragResponse.getCitations().size()))
                        .citationCount(ragResponse.getCitations() == null ? 0 : ragResponse.getCitations().size())
                        .costMs(System.currentTimeMillis() - actionStart)
                        .build())
                .toolGovernance(governanceDecision)
                .build();

        log.info("ReAct Agent | planner={} | action={} | confidence={} | hit={} | citations={}",
                plan.getPlannerType(),
                plan.getAction().getType(),
                plan.getConfidence(),
                ragResponse.getHit(),
                ragResponse.getCitations() == null ? 0 : ragResponse.getCitations().size());

        return AgentResponse.builder()
                .answer(ragResponse.getAnswer())
                .finalAction(AgentActionType.FINAL_ANSWER)
                .strategy("REACT_HYBRID_RAG")
                .citations(ragResponse.getCitations())
                .reactTrace(List.of(step))
                .costMs(System.currentTimeMillis() - start)
                .modelCostMs(ragResponse.getModelCostMs())
                .retrievalCostMs(ragResponse.getRetrievalCostMs())
                .estimatedInputTokens(ragResponse.getEstimatedInputTokens())
                .contextTruncated(ragResponse.getContextTruncated())
                .toolGovernance(governanceDecision)
                .build();
    }

    private AgentResponse answerWithCurrentTime(Long userId,
                                                Long sessionId,
                                                String prompt,
                                                AgentPlan plan,
                                                ToolGovernanceDecision governanceDecision,
                                                long start) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String answer = """
                回答：
                当前上海时间是 %s。

                引用：
                无
        """.formatted(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA)));
        saveMemory(sessionId, prompt, answer);
        memoryAgent.afterAnswer(userId, sessionId, prompt);

        ReActStep step = ReActStep.builder()
                .step(1)
                .thought(plan.getThought())
                .needRetrieval(plan.getNeedRetrieval())
                .actionReason(plan.getActionReason())
                .confidence(plan.getConfidence())
                .action(plan.getAction())
                .observation(AgentObservation.builder()
                        .success(true)
                        .summary("system clock returned Asia/Shanghai current time")
                        .citationCount(0)
                        .costMs(System.currentTimeMillis() - start)
                        .build())
                .toolGovernance(governanceDecision)
                .build();

        return AgentResponse.builder()
                .answer(answer)
                .finalAction(AgentActionType.FINAL_ANSWER)
                .strategy("REACT_TOOL")
                .citations(List.of())
                .reactTrace(List.of(step))
                .costMs(System.currentTimeMillis() - start)
                .modelCostMs(0L)
                .retrievalCostMs(0L)
                .estimatedInputTokens(0)
                .contextTruncated(false)
                .toolGovernance(governanceDecision)
                .build();
    }

    private AgentResponse answerDirectly(Long userId,
                                         Long sessionId,
                                         String prompt,
                                         AgentPlan plan,
                                         MemoryContext memoryContext,
                                         ToolGovernanceDecision governanceDecision,
                                         long start) {
        long modelStart = System.currentTimeMillis();
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(
                        SystemMessage.from("""
                                你是千言 Agent。对于闲聊、常识性问题或不需要企业知识库的问题，直接简洁回答。
                                输出必须使用固定格式：
                                回答：
                                xxx

                                引用：
                                无
                                """),
                        UserMessage.from(buildDirectUserPrompt(prompt, memoryContext))
                )
                .maxOutputTokens(maxOutputTokens)
                .build());
        long modelCostMs = System.currentTimeMillis() - modelStart;
        String answer = ensureDirectAnswerFormat(response.aiMessage().text());
        saveMemory(sessionId, prompt, answer);
        memoryAgent.afterAnswer(userId, sessionId, prompt);

        ReActStep step = ReActStep.builder()
                .step(1)
                .thought(plan.getThought())
                .needRetrieval(plan.getNeedRetrieval())
                .actionReason(plan.getActionReason())
                .confidence(plan.getConfidence())
                .action(plan.getAction())
                .observation(AgentObservation.builder()
                        .success(true)
                        .summary("answered without retrieval or tool call")
                        .citationCount(0)
                        .costMs(modelCostMs)
                        .build())
                .toolGovernance(governanceDecision)
                .build();

        return AgentResponse.builder()
                .answer(answer)
                .finalAction(AgentActionType.FINAL_ANSWER)
                .strategy("REACT_DIRECT")
                .citations(List.of())
                .reactTrace(List.of(step))
                .costMs(System.currentTimeMillis() - start)
                .modelCostMs(modelCostMs)
                .retrievalCostMs(0L)
                .estimatedInputTokens(estimateTokens(prompt))
                .contextTruncated(false)
                .toolGovernance(governanceDecision)
                .build();
    }

    private AgentResponse blockedByGovernance(String prompt,
                                              AgentPlan plan,
                                              ToolGovernanceDecision governanceDecision,
                                              long start) {
        String answer = """
                回答：
                工具调用已被权限护轨拦截：%s

                引用：
                无
                """.formatted(governanceDecision.getReason());
        ReActStep step = ReActStep.builder()
                .step(1)
                .thought(plan.getThought())
                .needRetrieval(plan.getNeedRetrieval())
                .actionReason(plan.getActionReason())
                .confidence(plan.getConfidence())
                .action(plan.getAction())
                .toolGovernance(governanceDecision)
                .observation(AgentObservation.builder()
                        .success(false)
                        .summary("tool governance blocked execution")
                        .citationCount(0)
                        .costMs(System.currentTimeMillis() - start)
                        .build())
                .build();
        return AgentResponse.builder()
                .answer(answer)
                .finalAction(AgentActionType.FINAL_ANSWER)
                .strategy("REACT_TOOL_BLOCKED")
                .citations(List.of())
                .reactTrace(List.of(step))
                .costMs(System.currentTimeMillis() - start)
                .modelCostMs(0L)
                .retrievalCostMs(0L)
                .estimatedInputTokens(estimateTokens(prompt))
                .contextTruncated(false)
                .toolGovernance(governanceDecision)
                .build();
    }

    private String normalizePrompt(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }

    private String ensureDirectAnswerFormat(String answer) {
        if (answer == null || answer.isBlank()) {
            return "回答：\n我暂时没有生成有效回答。\n\n引用：\n无";
        }
        boolean hasAnswer = answer.contains("回答：");
        boolean hasCitation = answer.contains("引用：");
        if (hasAnswer && hasCitation) {
            return answer;
        }
        return "回答：\n" + answer.strip() + "\n\n引用：\n无";
    }

    private String buildDirectUserPrompt(String prompt, MemoryContext memoryContext) {
        return """
                记忆上下文：
                %s

                用户问题：
                %s
                """.formatted(memoryContextText(memoryContext), prompt);
    }

    private String memoryContextText(MemoryContext memoryContext) {
        if (memoryContext == null
                || (!Boolean.TRUE.equals(memoryContext.getSummaryInjected())
                && !Boolean.TRUE.equals(memoryContext.getLongTermMemoryInjected()))) {
            return "暂无。";
        }
        StringBuilder builder = new StringBuilder();
        if (Boolean.TRUE.equals(memoryContext.getSummaryInjected())) {
            builder.append("会话摘要：\n")
                    .append(memoryContext.getSessionSummary())
                    .append("\n");
        }
        if (Boolean.TRUE.equals(memoryContext.getLongTermMemoryInjected())) {
            builder.append("长期记忆：\n");
            for (MemoryItem memory : memoryContext.getLongTermMemories()) {
                builder.append("- [")
                        .append(memory.getMemoryType())
                        .append("] ")
                        .append(memory.getSummary() == null || memory.getSummary().isBlank()
                                ? memory.getContent()
                                : memory.getSummary())
                        .append("\n");
            }
        }
        return builder.toString().strip();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 2.0);
    }

    private void saveMemory(Long sessionId, String prompt, String answer) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .id(sessionId == null ? "agent-default-session" : sessionId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(memoryMaxMessages)
                .build();
        chatMemory.add(UserMessage.from(prompt));
        chatMemory.add(AiMessage.from(answer));
    }
}
