package com.lou.infinitechatagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class BgeRerankService implements RerankService {

    @Resource
    private RuleBasedRerankService fallbackRerankService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RestClient.Builder restClientBuilder;

    @Value("${rag.rerank.provider:bge}")
    private String provider;

    @Value("${rag.rerank.endpoint:http://localhost:8080/rerank}")
    private String endpoint;

    @Value("${rag.rerank.model:BAAI/bge-reranker-v2-m3}")
    private String model;

    @Value("${rag.rerank.request-format:tei}")
    private String requestFormat;

    @Value("${rag.rerank.api-key:}")
    private String apiKey;

    @Value("${rag.rerank.max-document-chars:3500}")
    private int maxDocumentChars;

    @Value("${rag.rerank.failure-cooldown-ms:60000}")
    private long failureCooldownMs;

    private volatile long unavailableUntilMs = 0L;

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        if (isRuleBasedProvider()) {
            return fallbackRerankService.rerank(query, candidates, topK);
        }
        if (!isBgeProvider()) {
            log.warn("RAG Rerank - 未识别的 provider={}，自动降级为规则重排", provider);
            return fallbackRerankService.rerank(query, candidates, topK);
        }
        if (!StringUtils.hasText(endpoint)) {
            log.warn("RAG Rerank - 未配置 BGE rerank endpoint，自动降级为规则重排");
            return fallbackRerankService.rerank(query, candidates, topK);
        }
        if (System.currentTimeMillis() < unavailableUntilMs) {
            return fallbackRerankService.rerank(query, candidates, topK);
        }
        try {
            List<String> documents = candidates.stream()
                    .map(this::toRerankDocument)
                    .toList();
            String responseBody = postRerankRequest(query, documents, Math.min(topK, candidates.size()));
            List<RetrievedChunk> reranked = parseResponse(responseBody, candidates, topK);
            if (!reranked.isEmpty()) {
                log.info("RAG Rerank - BGE 模型 [{}] 完成重排，before={} after={}", model, candidates.size(), reranked.size());
                return reranked;
            }
        } catch (Exception e) {
            unavailableUntilMs = System.currentTimeMillis() + Math.max(1000, failureCooldownMs);
            log.warn("RAG Rerank - BGE 模型 [{}] 调用失败，endpoint={}，{}ms 内自动使用规则重排。原因: {}",
                    model,
                    endpoint,
                    Math.max(1000, failureCooldownMs),
                    rootCauseMessage(e));
        }
        return fallbackRerankService.rerank(query, candidates, topK);
    }

    private String postRerankRequest(String query, List<String> documents, int topK) {
        RestClient.RequestBodySpec requestSpec = restClientBuilder.build()
                .post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(apiKey)) {
            requestSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        return requestSpec
                .body(buildRequestBody(query, documents, topK))
                .retrieve()
                .body(String.class);
    }

    private Map<String, Object> buildRequestBody(String query, List<String> documents, int topK) {
        if (isTeiFormat()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("texts", documents);
            body.put("truncate", true);
            return body;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", topK);
        return body;
    }

    private List<RetrievedChunk> parseResponse(String responseBody, List<RetrievedChunk> candidates, int topK) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode results = resolveResultsNode(root);
        if (!results.isArray()) {
            throw new IllegalStateException("rerank response missing results array");
        }
        List<RerankResult> scoredResults = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JsonNode result = results.get(i);
            int index = readIndex(result, i);
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            scoredResults.add(new RerankResult(index, readScore(result)));
        }
        return scoredResults.stream()
                .sorted(Comparator.comparing(RerankResult::score).reversed())
                .limit(topK)
                .map(result -> {
                    RetrievedChunk chunk = candidates.get(result.index());
                    chunk.setRerankScore(result.score());
                    return chunk;
                })
                .toList();
    }

    private JsonNode resolveResultsNode(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        if (root.path("results").isArray()) {
            return root.path("results");
        }
        if (root.path("data").isArray()) {
            return root.path("data");
        }
        if (root.path("output").path("results").isArray()) {
            return root.path("output").path("results");
        }
        return root.path("results");
    }

    private int readIndex(JsonNode result, int defaultIndex) {
        if (result.has("index")) {
            return result.path("index").asInt(defaultIndex);
        }
        if (result.has("corpus_id")) {
            return result.path("corpus_id").asInt(defaultIndex);
        }
        return defaultIndex;
    }

    private double readScore(JsonNode result) {
        if (result.has("score")) {
            return result.path("score").asDouble(0);
        }
        if (result.has("relevance_score")) {
            return result.path("relevance_score").asDouble(0);
        }
        return result.path("rank_score").asDouble(0);
    }

    private String toRerankDocument(RetrievedChunk chunk) {
        StringBuilder builder = new StringBuilder();
        appendIfPresent(builder, "文件", chunk.getFileName());
        appendIfPresent(builder, "章节", displaySection(chunk));
        appendIfPresent(builder, "内容", chunk.getText());
        return limitText(builder.toString().strip());
    }

    private String displaySection(RetrievedChunk chunk) {
        if (StringUtils.hasText(chunk.getHeadingPath())) {
            return chunk.getHeadingPath();
        }
        return chunk.getSectionTitle();
    }

    private void appendIfPresent(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(label).append("：").append(value.strip()).append('\n');
        }
    }

    private String limitText(String text) {
        int maxChars = Math.max(500, maxDocumentChars);
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private boolean isBgeProvider() {
        return "bge".equalsIgnoreCase(provider)
                || "local-bge".equalsIgnoreCase(provider)
                || "http-bge".equalsIgnoreCase(provider);
    }

    private boolean isRuleBasedProvider() {
        return "rule".equalsIgnoreCase(provider)
                || "rule_based".equalsIgnoreCase(provider)
                || "rule-based".equalsIgnoreCase(provider);
    }

    private boolean isTeiFormat() {
        return "tei".equalsIgnoreCase(requestFormat)
                || "text-embeddings-inference".equalsIgnoreCase(requestFormat);
    }

    private String rootCauseMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (StringUtils.hasText(message)) {
            return cause.getClass().getSimpleName() + ": " + message;
        }
        return cause.getClass().getSimpleName();
    }

    private record RerankResult(int index, double score) {
    }
}
