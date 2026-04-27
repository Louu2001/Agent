package com.lou.infinitechatagent.rag.adaptive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagRequest;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalStrategy;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LlmRetrievalPlanner implements RetrievalPlanner {

    @Resource
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RuleBasedRetrievalPlanner ruleBasedRetrievalPlanner;

    @Value("${rag.adaptive.knowledge-base:agent_docs}")
    private String knowledgeBase;

    @Value("${rag.adaptive.retrieval.vector-top-k:20}")
    private int defaultVectorTopK;

    @Value("${rag.adaptive.retrieval.keyword-top-k:20}")
    private int defaultKeywordTopK;

    @Value("${rag.adaptive.retrieval.rerank-top-k:5}")
    private int defaultRerankTopK;

    @Value("${rag.adaptive.planner.max-output-tokens:300}")
    private int maxOutputTokens;

    @Override
    public RetrievalPlan plan(AdaptiveRagRequest request) {
        String prompt = normalize(request.getPrompt());
        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(
                            SystemMessage.from(buildSystemPrompt()),
                            UserMessage.from("用户问题：" + prompt)
                    )
                    .maxOutputTokens(maxOutputTokens)
                    .build());
            return parsePlan(prompt, response.aiMessage().text());
        } catch (Exception exception) {
            log.warn("LLM retrieval planner failed, fallback to rule based planner. reason={}", exception.getMessage());
            RetrievalPlan fallback = ruleBasedRetrievalPlanner.plan(request);
            fallback.setReason("LLM Retrieval Planner 失败，降级规则 Planner：" + fallback.getReason());
            return fallback;
        }
    }

    private RetrievalPlan parsePlan(String prompt, String plannerOutput) throws Exception {
        String json = extractJson(plannerOutput);
        JsonNode node = objectMapper.readTree(json);
        RetrievalStrategy strategy = RetrievalStrategy.valueOf(node.path("strategy").asText("HYBRID"));
        boolean needRetrieval = node.path("needRetrieval").asBoolean(strategy != RetrievalStrategy.NO_RETRIEVAL
                && strategy != RetrievalStrategy.FOLLOW_UP_REQUIRED);
        String query = node.path("query").asText(prompt);
        String reason = node.path("reason").asText("LLM 判断检索策略。");
        double confidence = clamp(node.path("confidence").asDouble(0.7));

        return RetrievalPlan.builder()
                .needRetrieval(needRetrieval)
                .knowledgeBase(node.path("knowledgeBase").asText(knowledgeBase))
                .strategy(strategy)
                .query(query == null || query.isBlank() ? prompt : query)
                .vectorTopK(clampTopK(node.path("vectorTopK").asInt(defaultVectorTopK), 1, 50))
                .keywordTopK(clampTopK(node.path("keywordTopK").asInt(defaultKeywordTopK), 1, 50))
                .rerankTopK(clampTopK(node.path("rerankTopK").asInt(defaultRerankTopK), 1, 10))
                .reason(reason)
                .confidence(confidence)
                .build();
    }

    private String buildSystemPrompt() {
        return """
                你是 Adaptive RAG 的 Retrieval Planner，只负责制定检索计划，不回答用户问题。
                你必须从以下 strategy 中选择一个：
                1. NO_RETRIEVAL：闲聊、润色、翻译、通用解释，不需要企业知识库。
                2. FOLLOW_UP_REQUIRED：问题信息不足，需要用户补充错误码、配置项、接口名或业务对象。
                3. VECTOR：概念解释、流程总结、语义相似问题。
                4. KEYWORD：错误码、配置项、类名、方法名、接口名等精确匹配问题。
                5. HYBRID：既需要语义理解又需要精确词匹配的企业知识库问题。

                只输出 JSON，不要输出 Markdown，不要解释。
                JSON 字段：
                {
                  "needRetrieval": true 或 false,
                  "knowledgeBase": "agent_docs",
                  "strategy": "NO_RETRIEVAL | FOLLOW_UP_REQUIRED | VECTOR | KEYWORD | HYBRID",
                  "query": "适合检索的查询语句",
                  "vectorTopK": 1 到 50,
                  "keywordTopK": 1 到 50,
                  "rerankTopK": 1 到 10,
                  "reason": "选择该检索计划的原因，中文一句话",
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

    private int clampTopK(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String normalize(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }
}
