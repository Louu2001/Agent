package com.lou.infinitechatagent.agent;

import com.lou.infinitechatagent.agent.context.AgentContext;
import com.lou.infinitechatagent.agent.context.AgentContextManager;
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
import com.lou.infinitechatagent.agent.tool.WebSearchResult;
import com.lou.infinitechatagent.agent.tool.WebSearchResultItem;
import com.lou.infinitechatagent.agent.tool.WebSearchService;
import com.lou.infinitechatagent.memory.LongTermMemoryService;
import com.lou.infinitechatagent.memory.MemoryRetrievalService;
import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.MemoryType;
import com.lou.infinitechatagent.memory.dto.MemoryWriteRequest;
import com.lou.infinitechatagent.rag.RagQueryService;
import com.lou.infinitechatagent.rag.dto.RagQueryResponse;
import com.lou.infinitechatagent.tool.EmailTool;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ReActAgentOrchestrator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    @Resource
    private RagQueryService ragQueryService;

    @Resource
    private ChatModel chatModel;

    @Resource
    private AgentContextManager agentContextManager;

    @Resource
    private RuleBasedAgentPlanner ruleBasedAgentPlanner;

    @Resource
    private LlmAgentPlanner llmAgentPlanner;

    @Resource
    private ToolGovernanceService toolGovernanceService;

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Resource
    private MemoryRetrievalService memoryRetrievalService;

    @Resource
    private EmailTool emailTool;

    @Resource
    private WebSearchService webSearchService;

    @Value("${agent.react.max-output-tokens:500}")
    private int maxOutputTokens;

    @Value("${agent.react.planner.mode:RULE_BASED}")
    private String plannerMode;

    public AgentResponse chat(AgentRequest request) {
        long start = System.currentTimeMillis();
        String prompt = normalizePrompt(request.getPrompt());
        AgentContext agentContext = agentContextManager.prepare(request.getUserId(), request.getSessionId(), prompt);
        AgentPlan plan = plan(prompt);
        AgentAction action = plan.getAction();
        ToolGovernanceDecision governanceDecision = toolGovernanceService.evaluate(
                request.getUserId(),
                request.getSessionId(),
                prompt,
                action,
                request.getConfirmedTools());

        if (!Boolean.TRUE.equals(governanceDecision.getAllowed())) {
            return blockedByGovernance(prompt, plan, governanceDecision, agentContext, start);
        }

        return switch (action.getType()) {
            case HYBRID_SEARCH -> answerWithRag(request.getUserId(), request.getSessionId(), prompt, plan, agentContext, governanceDecision, start);
            case CURRENT_TIME -> answerWithCurrentTime(request.getUserId(), request.getSessionId(), prompt, plan, agentContext, governanceDecision, start);
            case MEMORY_WRITE -> answerWithMemoryWrite(request.getUserId(), request.getSessionId(), prompt, plan, agentContext, governanceDecision, start);
            case MEMORY_SEARCH -> answerWithMemorySearch(request.getUserId(), request.getSessionId(), prompt, plan, agentContext, governanceDecision, start);
            case EMAIL_SEND -> answerWithEmailSend(request.getUserId(), request.getSessionId(), prompt, plan, agentContext, governanceDecision, start);
            case WEB_SEARCH -> answerWithWebSearch(request.getUserId(), request.getSessionId(), prompt, plan, agentContext, governanceDecision, start);
            case NO_RETRIEVAL_ANSWER -> answerDirectly(request.getUserId(), request.getSessionId(), prompt, plan, agentContext, governanceDecision, start);
            default -> answerDirectly(request.getUserId(), request.getSessionId(), prompt, plan, agentContext, governanceDecision, start);
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
                                        AgentContext agentContext,
                                        ToolGovernanceDecision governanceDecision,
                                        long start) {
        long actionStart = System.currentTimeMillis();
        RagQueryResponse ragResponse = ragQueryService.chatWithCitations(sessionId, prompt, agentContext);
        agentContextManager.afterAnswer(userId, sessionId, prompt);
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
                .contextTruncated(Boolean.TRUE.equals(ragResponse.getContextTruncated())
                        || Boolean.TRUE.equals(agentContext.getContextTruncated()))
                .memoryTrace(agentContext.getMemoryTrace())
                .toolGovernance(governanceDecision)
                .build();
    }

    private AgentResponse answerWithCurrentTime(Long userId,
                                                Long sessionId,
                                                String prompt,
                                                AgentPlan plan,
                                                AgentContext agentContext,
                                                ToolGovernanceDecision governanceDecision,
                                                long start) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String answer = """
                回答：
                当前上海时间是 %s。

                引用：
                无
        """.formatted(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA)));
        agentContextManager.saveTurn(sessionId, prompt, answer);
        agentContextManager.afterAnswer(userId, sessionId, prompt);

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
                .estimatedInputTokens(agentContext.getEstimatedInputTokens())
                .contextTruncated(agentContext.getContextTruncated())
                .memoryTrace(agentContext.getMemoryTrace())
                .toolGovernance(governanceDecision)
                .build();
    }

    private AgentResponse answerWithMemoryWrite(Long userId,
                                                Long sessionId,
                                                String prompt,
                                                AgentPlan plan,
                                                AgentContext agentContext,
                                                ToolGovernanceDecision governanceDecision,
                                                long start) {
        long actionStart = System.currentTimeMillis();
        if (userId == null) {
            String answer = "回答：\n写入长期记忆需要提供 userId。\n\n引用：\n无";
            return completeToolResponse(userId, sessionId, prompt, plan, agentContext, governanceDecision, start,
                    actionStart, answer, "REACT_MEMORY_WRITE", false, "memory write skipped because userId is missing");
        }

        String memoryContent = firstTextArgument(plan, "memoryContent", "content");
        if (!StringUtils.hasText(memoryContent)) {
            memoryContent = cleanMemoryContent(prompt);
        }
        MemoryType memoryType = resolveMemoryType(firstTextArgument(plan, "memoryType"), memoryContent);
        MemoryWriteRequest writeRequest = new MemoryWriteRequest();
        writeRequest.setUserId(userId);
        writeRequest.setSessionId(sessionId);
        writeRequest.setMemoryType(memoryType);
        writeRequest.setContent(memoryContent);
        writeRequest.setSummary(limitText(memoryContent, 180));
        writeRequest.setConfidence(0.9);
        writeRequest.setSource("react_agent");
        MemoryItem memoryItem = longTermMemoryService.writeWithDedup(writeRequest);

        String answer = """
                回答：
                已写入长期记忆。
                - memoryId：%s
                - 类型：%s
                - 内容：%s

                引用：
                无
                """.formatted(memoryItem.getMemoryId(), memoryItem.getMemoryType(), memoryText(memoryItem));
        return completeToolResponse(userId, sessionId, prompt, plan, agentContext, governanceDecision, start,
                actionStart, answer, "REACT_MEMORY_WRITE", true, "memory written: " + memoryItem.getMemoryId());
    }

    private AgentResponse answerWithMemorySearch(Long userId,
                                                 Long sessionId,
                                                 String prompt,
                                                 AgentPlan plan,
                                                 AgentContext agentContext,
                                                 ToolGovernanceDecision governanceDecision,
                                                 long start) {
        long actionStart = System.currentTimeMillis();
        if (userId == null) {
            String answer = "回答：\n查询长期记忆需要提供 userId。\n\n引用：\n无";
            return completeToolResponse(userId, sessionId, prompt, plan, agentContext, governanceDecision, start,
                    actionStart, answer, "REACT_MEMORY_SEARCH", false, "memory search skipped because userId is missing");
        }
        List<MemoryItem> memories = memoryRetrievalService.retrieveRelevantMemories(userId, prompt);
        String answer = memories.isEmpty()
                ? "回答：\n没有找到与当前问题相关的长期记忆。\n\n引用：\n无"
                : "回答：\n" + formatMemories(memories) + "\n\n引用：\n长期记忆";
        return completeToolResponse(userId, sessionId, prompt, plan, agentContext, governanceDecision, start,
                actionStart, answer, "REACT_MEMORY_SEARCH", true, "memory search returned " + memories.size() + " items");
    }

    private AgentResponse answerWithEmailSend(Long userId,
                                              Long sessionId,
                                              String prompt,
                                              AgentPlan plan,
                                              AgentContext agentContext,
                                              ToolGovernanceDecision governanceDecision,
                                              long start) {
        long actionStart = System.currentTimeMillis();
        String targetEmail = firstTextArgument(plan, "targetEmail", "email", "to");
        if (!StringUtils.hasText(targetEmail)) {
            targetEmail = extractEmail(prompt);
        }
        if (!StringUtils.hasText(targetEmail)) {
            String answer = "回答：\n没有识别到收件人邮箱，邮件未发送。\n\n引用：\n无";
            return completeToolResponse(userId, sessionId, prompt, plan, agentContext, governanceDecision, start,
                    actionStart, answer, "REACT_EMAIL_SEND", false, "email send skipped because target email is missing");
        }
        String subject = firstTextArgument(plan, "subject");
        if (!StringUtils.hasText(subject)) {
            subject = "来自 InfiniteChat-Agent 的消息";
        }
        String content = firstTextArgument(plan, "content", "body");
        if (!StringUtils.hasText(content)) {
            content = cleanEmailContent(prompt, targetEmail);
        }
        String toolResult = emailTool.sendEmail(targetEmail, subject, content);
        String answer = """
                回答：
                %s
                - 收件人：%s
                - 标题：%s

                引用：
                无
                """.formatted(toolResult, targetEmail, subject);
        return completeToolResponse(userId, sessionId, prompt, plan, agentContext, governanceDecision, start,
                actionStart, answer, "REACT_EMAIL_SEND", toolResult.contains("成功"), "email tool executed for " + targetEmail);
    }

    private AgentResponse answerWithWebSearch(Long userId,
                                              Long sessionId,
                                              String prompt,
                                              AgentPlan plan,
                                              AgentContext agentContext,
                                              ToolGovernanceDecision governanceDecision,
                                              long start) {
        long actionStart = System.currentTimeMillis();
        WebSearchResult result = webSearchService.search(prompt);
        String answer = Boolean.TRUE.equals(result.getSuccess()) && result.getResults() != null && !result.getResults().isEmpty()
                ? "回答：\n" + formatWebSearchResults(result.getResults()) + "\n\n引用：\n联网搜索结果"
                : "回答：\n" + result.getMessage() + "\n\n引用：\n无";
        return completeToolResponse(userId, sessionId, prompt, plan, agentContext, governanceDecision, start,
                actionStart, answer, "REACT_WEB_SEARCH", Boolean.TRUE.equals(result.getSuccess()),
                "web search results=" + (result.getResults() == null ? 0 : result.getResults().size()));
    }

    private AgentResponse answerDirectly(Long userId,
                                         Long sessionId,
                                         String prompt,
                                         AgentPlan plan,
                                         AgentContext agentContext,
                                         ToolGovernanceDecision governanceDecision,
                                         long start) {
        long modelStart = System.currentTimeMillis();
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(
                        SystemMessage.from("""
                                你是千言 Agent。对于闲聊、常识性问题或不需要企业知识库的问题，结合可用记忆和最近对话直接简洁回答。
                                如果记忆上下文与用户问题无关，请忽略它，不要主动暴露系统记忆细节。
                                输出必须使用固定格式：
                                回答：
                                xxx

                                引用：
                                无
                                """),
                        UserMessage.from(agentContextManager.buildDirectPrompt(prompt, agentContext))
                )
                .maxOutputTokens(maxOutputTokens)
                .build());
        long modelCostMs = System.currentTimeMillis() - modelStart;
        String answer = ensureDirectAnswerFormat(response.aiMessage().text());
        agentContextManager.saveTurn(sessionId, prompt, answer);
        agentContextManager.afterAnswer(userId, sessionId, prompt);

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
                .estimatedInputTokens(agentContext.getEstimatedInputTokens())
                .contextTruncated(agentContext.getContextTruncated())
                .memoryTrace(agentContext.getMemoryTrace())
                .toolGovernance(governanceDecision)
                .build();
    }

    private AgentResponse blockedByGovernance(String prompt,
                                              AgentPlan plan,
                                              ToolGovernanceDecision governanceDecision,
                                              AgentContext agentContext,
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
                .estimatedInputTokens(agentContext.getEstimatedInputTokens())
                .contextTruncated(agentContext.getContextTruncated())
                .memoryTrace(agentContext.getMemoryTrace())
                .toolGovernance(governanceDecision)
                .build();
    }

    private AgentResponse completeToolResponse(Long userId,
                                               Long sessionId,
                                               String prompt,
                                               AgentPlan plan,
                                               AgentContext agentContext,
                                               ToolGovernanceDecision governanceDecision,
                                               long start,
                                               long actionStart,
                                               String answer,
                                               String strategy,
                                               boolean success,
                                               String observationSummary) {
        agentContextManager.saveTurn(sessionId, prompt, answer);
        agentContextManager.afterAnswer(userId, sessionId, prompt);
        ReActStep step = ReActStep.builder()
                .step(1)
                .thought(plan.getThought())
                .needRetrieval(plan.getNeedRetrieval())
                .actionReason(plan.getActionReason())
                .confidence(plan.getConfidence())
                .action(plan.getAction())
                .observation(AgentObservation.builder()
                        .success(success)
                        .summary(observationSummary)
                        .citationCount(0)
                        .costMs(System.currentTimeMillis() - actionStart)
                        .build())
                .toolGovernance(governanceDecision)
                .build();
        return AgentResponse.builder()
                .answer(answer)
                .finalAction(AgentActionType.FINAL_ANSWER)
                .strategy(strategy)
                .citations(List.of())
                .reactTrace(List.of(step))
                .costMs(System.currentTimeMillis() - start)
                .modelCostMs(0L)
                .retrievalCostMs(0L)
                .estimatedInputTokens(agentContext.getEstimatedInputTokens())
                .contextTruncated(agentContext.getContextTruncated())
                .memoryTrace(agentContext.getMemoryTrace())
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

    private String firstTextArgument(AgentPlan plan, String... names) {
        Map<String, Object> arguments = plan == null
                || plan.getAction() == null
                ? null
                : plan.getAction().getArguments();
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        for (String name : names) {
            Object value = arguments.get(name);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text.strip();
            }
        }
        return "";
    }

    private MemoryType resolveMemoryType(String typeText, String content) {
        if (StringUtils.hasText(typeText)) {
            try {
                return MemoryType.valueOf(typeText.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        String text = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (text.contains("技术栈") || text.contains("spring") || text.contains("java") || text.contains("redis") || text.contains("mysql")) {
            return MemoryType.TECH_STACK;
        }
        if (text.contains("项目") || text.contains("agent") || text.contains("rag")) {
            return MemoryType.PROJECT_CONTEXT;
        }
        if (text.contains("偏好") || text.contains("喜欢") || text.contains("习惯")) {
            return MemoryType.USER_PREFERENCE;
        }
        if (text.contains("格式") || text.contains("输出") || text.contains("风格")) {
            return MemoryType.OUTPUT_STYLE;
        }
        return MemoryType.IMPORTANT_FACT;
    }

    private String cleanMemoryContent(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return "";
        }
        return prompt.strip()
                .replaceFirst("^(请)?(帮我)?记住[:：,，\\s]*", "")
                .replaceFirst("^(请)?记下[:：,，\\s]*", "")
                .replaceFirst("^以后记得[:：,，\\s]*", "")
                .strip();
    }

    private String formatMemories(List<MemoryItem> memories) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < memories.size(); i++) {
            MemoryItem memory = memories.get(i);
            builder.append(i + 1)
                    .append(". [")
                    .append(memory.getMemoryType())
                    .append("] ")
                    .append(memoryText(memory))
                    .append("\n");
        }
        return builder.toString().strip();
    }

    private String memoryText(MemoryItem memory) {
        if (memory == null) {
            return "";
        }
        return StringUtils.hasText(memory.getSummary()) ? memory.getSummary() : memory.getContent();
    }

    private String extractEmail(String prompt) {
        Matcher matcher = EMAIL_PATTERN.matcher(prompt == null ? "" : prompt);
        return matcher.find() ? matcher.group() : "";
    }

    private String cleanEmailContent(String prompt, String targetEmail) {
        if (!StringUtils.hasText(prompt)) {
            return "";
        }
        return prompt.replace(targetEmail, "")
                .replace("发送邮件", "")
                .replace("发邮件", "")
                .replace("发一封邮件", "")
                .strip();
    }

    private String formatWebSearchResults(List<WebSearchResultItem> results) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            WebSearchResultItem item = results.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(StringUtils.hasText(item.getTitle()) ? item.getTitle() : "未命名结果")
                    .append("\n")
                    .append("   ")
                    .append(limitText(item.getContent(), 220))
                    .append("\n")
                    .append("   ")
                    .append(item.getUrl())
                    .append("\n");
        }
        return builder.toString().strip();
    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "...";
    }

}
