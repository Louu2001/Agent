package com.lou.infinitechatagent.rag.adaptive;

import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagRequest;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalStrategy;
import org.springframework.beans.factory.annotation.Value;

import java.util.Locale;
import java.util.regex.Pattern;

public class RuleBasedRetrievalPlanner implements RetrievalPlanner {

    private static final Pattern CODE_OR_IDENTIFIER_PATTERN = Pattern.compile(
            "([A-Z]{2,}-\\d+)|([a-zA-Z][a-zA-Z0-9_]+\\.[a-zA-Z][a-zA-Z0-9_]+)|(@[A-Za-z]+)"
    );

    @Value("${rag.adaptive.knowledge-base:agent_docs}")
    private String knowledgeBase;

    @Value("${rag.adaptive.retrieval.vector-top-k:20}")
    private int vectorTopK;

    @Value("${rag.adaptive.retrieval.keyword-top-k:20}")
    private int keywordTopK;

    @Value("${rag.adaptive.retrieval.rerank-top-k:5}")
    private int rerankTopK;

    @Override
    public RetrievalPlan plan(AdaptiveRagRequest request) {
        String prompt = normalize(request.getPrompt());
        if (prompt.isBlank()) {
            return buildPlan(false, RetrievalStrategy.NO_RETRIEVAL, prompt, "用户输入为空，不触发检索。", 0.99);
        }
        if (isFollowUpRequired(prompt)) {
            return buildPlan(false, RetrievalStrategy.FOLLOW_UP_REQUIRED, prompt, "问题缺少错误码、配置项或业务对象，需要先追问补充信息。", 0.78);
        }
        if (!shouldRetrieve(prompt)) {
            return buildPlan(false, RetrievalStrategy.NO_RETRIEVAL, prompt, "问题不依赖企业私有知识库，可以直接回答。", 0.82);
        }
        if (isKeywordHeavy(prompt)) {
            return buildPlan(true, RetrievalStrategy.KEYWORD, prompt, "问题包含错误码、配置项、类名或方法名，优先使用关键词检索。", 0.88);
        }
        if (isSemanticQuestion(prompt)) {
            return buildPlan(true, RetrievalStrategy.VECTOR, prompt, "问题偏概念解释或流程总结，优先使用语义向量检索。", 0.8);
        }
        return buildPlan(true, RetrievalStrategy.HYBRID, prompt, "问题需要兼顾语义召回和精确词匹配，使用混合检索。", 0.84);
    }

    private RetrievalPlan buildPlan(boolean needRetrieval,
                                    RetrievalStrategy strategy,
                                    String query,
                                    String reason,
                                    double confidence) {
        return RetrievalPlan.builder()
                .needRetrieval(needRetrieval)
                .knowledgeBase(knowledgeBase)
                .strategy(strategy)
                .query(query)
                .vectorTopK(vectorTopK)
                .keywordTopK(keywordTopK)
                .rerankTopK(rerankTopK)
                .reason(reason)
                .confidence(confidence)
                .build();
    }

    private boolean shouldRetrieve(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        return text.contains("知识库")
                || text.contains("根据文档")
                || text.contains("引用")
                || text.contains("来源")
                || text.contains("rag")
                || text.contains("pgvector")
                || text.contains("redis")
                || text.contains("mcp")
                || text.contains("memoryid")
                || text.contains("配置")
                || text.contains("错误码")
                || text.contains("接口")
                || text.contains("类名")
                || text.contains("流程")
                || text.contains("架构")
                || CODE_OR_IDENTIFIER_PATTERN.matcher(prompt).find();
    }

    private boolean isKeywordHeavy(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        return text.contains("错误码")
                || text.contains("配置项")
                || text.contains("接口名")
                || text.contains("类名")
                || text.contains("方法名")
                || CODE_OR_IDENTIFIER_PATTERN.matcher(prompt).find();
    }

    private boolean isSemanticQuestion(String prompt) {
        return prompt.contains("为什么")
                || prompt.contains("是什么")
                || prompt.contains("说明")
                || prompt.contains("解释")
                || prompt.contains("流程")
                || prompt.contains("架构");
    }

    private boolean isFollowUpRequired(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        boolean vagueErrorQuestion = (prompt.contains("这个错误") || prompt.contains("这个异常") || prompt.contains("报错"))
                && !CODE_OR_IDENTIFIER_PATTERN.matcher(prompt).find();
        return vagueErrorQuestion
                || "这个怎么处理".equals(text)
                || "怎么处理".equals(text);
    }

    private String normalize(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }
}
