package com.lou.infinitechatagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lou.infinitechatagent.agent.dto.AgentAction;
import com.lou.infinitechatagent.agent.dto.AgentActionType;
import com.lou.infinitechatagent.agent.dto.AgentPlan;
import com.lou.infinitechatagent.agent.dto.AgentTool;
import com.lou.infinitechatagent.agent.tool.ToolRegistry;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class LlmAgentPlanner implements AgentPlanner {

    @Resource
    private ChatModel chatModel;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private RuleBasedAgentPlanner ruleBasedAgentPlanner;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${agent.react.planner.max-output-tokens:300}")
    private int maxOutputTokens;

    @Override
    public AgentPlan plan(String prompt) {
        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(
                            SystemMessage.from(buildSystemPrompt()),
                            UserMessage.from("用户问题：" + prompt)
                    )
                    .maxOutputTokens(maxOutputTokens)
                    .build());
            AgentPlan plan = parsePlan(prompt, response.aiMessage().text());
            if (plan != null) {
                return plan;
            }
        } catch (Exception exception) {
            log.warn("LLM planner failed, fallback to rule based planner. reason={}", exception.getMessage());
        }
        AgentPlan fallback = ruleBasedAgentPlanner.plan(prompt);
        fallback.setPlannerType("RULE_BASED_FALLBACK");
        return fallback;
    }

    private AgentPlan parsePlan(String prompt, String plannerOutput) throws Exception {
        String json = extractJson(plannerOutput);
        JsonNode node = objectMapper.readTree(json);
        AgentActionType actionType = AgentActionType.valueOf(node.path("actionType").asText());
        AgentTool tool = toolRegistry.requireTool(actionType);
        boolean needRetrieval = node.path("needRetrieval").asBoolean(actionType == AgentActionType.HYBRID_SEARCH);
        String reason = node.path("actionReason").asText(defaultReason(actionType));
        double confidence = clamp(node.path("confidence").asDouble(0.7));

        return AgentPlan.builder()
                .thought(reason)
                .needRetrieval(needRetrieval)
                .actionReason(reason)
                .confidence(confidence)
                .plannerType("LLM")
                .action(AgentAction.builder()
                        .type(actionType)
                        .toolName(tool.getName())
                        .query(prompt)
                        .arguments(Map.of(
                                "riskLevel", tool.getRiskLevel(),
                                "toolDescription", tool.getDescription(),
                                "plannerOutput", json
                        ))
                        .build())
                .build();
    }

    private String buildSystemPrompt() {
        return """
                你是 ReAct Agent 的 Planner，只负责选择下一步动作，不回答用户问题。
                你必须从以下动作中选择一个：
                1. NO_RETRIEVAL_ANSWER：闲聊、润色、翻译、通用解释，不需要企业知识库。
                2. CURRENT_TIME：查询当前时间、今天日期。
                3. HYBRID_SEARCH：涉及企业知识、私有文档、错误码、配置项、接口、类名、架构、流程、引用来源。

                只输出 JSON，不要输出 Markdown，不要解释。
                JSON 字段：
                {
                  "actionType": "NO_RETRIEVAL_ANSWER | CURRENT_TIME | HYBRID_SEARCH",
                  "needRetrieval": true 或 false,
                  "actionReason": "选择该动作的原因，中文一句话",
                  "confidence": 0.0 到 1.0
                }
                """;
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty planner output");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("planner output is not json: " + text);
        }
        return text.substring(start, end + 1);
    }

    private String defaultReason(AgentActionType actionType) {
        return switch (actionType) {
            case HYBRID_SEARCH -> "LLM 判断该问题需要检索企业知识库。";
            case CURRENT_TIME -> "LLM 判断该问题需要调用时间工具。";
            case NO_RETRIEVAL_ANSWER -> "LLM 判断该问题可以直接回答。";
            default -> "LLM 已完成动作规划。";
        };
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
