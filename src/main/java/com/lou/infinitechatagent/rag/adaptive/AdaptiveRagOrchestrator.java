package com.lou.infinitechatagent.rag.adaptive;

import com.lou.infinitechatagent.rag.HybridSearchService;
import com.lou.infinitechatagent.rag.KeywordSearchService;
import com.lou.infinitechatagent.rag.RerankService;
import com.lou.infinitechatagent.rag.VectorSearchService;
import com.lou.infinitechatagent.memory.MemoryAgent;
import com.lou.infinitechatagent.memory.dto.MemoryContext;
import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.MemoryTrace;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagRequest;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagCost;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagDebug;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagResponse;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagStep;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagToken;
import com.lou.infinitechatagent.rag.adaptive.dto.EvidenceEvaluation;
import com.lou.infinitechatagent.rag.adaptive.dto.QueryRewriteResult;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalStrategy;
import com.lou.infinitechatagent.rag.adaptive.dto.ScoreDetail;
import com.lou.infinitechatagent.rag.dto.Citation;
import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

@Service
@Slf4j
public class AdaptiveRagOrchestrator {

    private static final int RRF_K = 60;
    private static final String MISS_ANSWER = "当前知识库未提供足够信息，无法回答该问题。";

    @Resource
    private RetrievalPlanner retrievalPlanner;

    @Resource
    private VectorSearchService vectorSearchService;

    @Resource
    private KeywordSearchService keywordSearchService;

    @Resource
    private HybridSearchService hybridSearchService;

    @Resource
    private RerankService rerankService;

    @Resource
    private EvidenceEvaluator evidenceEvaluator;

    @Resource
    private QueryRewriteService queryRewriteService;

    @Resource
    private ChatModel chatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private MemoryAgent memoryAgent;

    @Value("${rag.adaptive.max-output-tokens:500}")
    private int maxOutputTokens;

    @Value("${rag.adaptive.memory-max-messages:20}")
    private int memoryMaxMessages;

    @Value("${rag.adaptive.max-rounds:2}")
    private int maxRounds;

    @Value("${rag.adaptive.rewrite.enabled:true}")
    private boolean rewriteEnabled;

    @Value("${rag.token.max-input-tokens:1800}")
    private int maxInputTokens;

    @Value("${rag.token.reserved-system-tokens:300}")
    private int reservedSystemTokens;

    @Value("${rag.token.min-chunk-chars:180}")
    private int minChunkChars;

    @Value("${rag.token.chars-per-token:2.0}")
    private double charsPerToken;

    public AdaptiveRagResponse chat(AdaptiveRagRequest request) {
        long start = System.currentTimeMillis();
        String prompt = normalize(request.getPrompt());
        MemoryTrace memoryTrace = memoryAgent.readContext(request.getUserId(), request.getSessionId(), prompt);
        MemoryContext memoryContext = memoryTrace.getContext();
        RetrievalPlan plan = retrievalPlanner.plan(request);

        if (!Boolean.TRUE.equals(plan.getNeedRetrieval())) {
            return handleNoRetrieval(request.getUserId(), request.getSessionId(), prompt, plan, memoryContext, start, request.getDebug());
        }

        long retrievalStart = System.currentTimeMillis();
        List<AdaptiveRagStep> trace = new ArrayList<>();
        List<QueryRewriteResult> rewrites = new ArrayList<>();
        List<RetrievedChunk> finalChunks = List.of();
        List<RetrievedChunk> finalCandidates = List.of();
        EvidenceEvaluation evaluation = null;
        RetrievalPlan currentPlan = plan;

        for (int round = 1; round <= maxRounds; round++) {
            RetrievalRoundResult roundResult = executeRetrievalRound(prompt, currentPlan, round);
            trace.add(roundResult.step());
            finalChunks = roundResult.rerankedChunks();
            finalCandidates = roundResult.candidates();
            evaluation = roundResult.evaluation();

            if (Boolean.TRUE.equals(evaluation.getSufficient())) {
                break;
            }
            if (!shouldRewrite(round, evaluation)) {
                break;
            }
            QueryRewriteResult rewrite = queryRewriteService.rewrite(prompt, currentPlan, evaluation, roundResult.candidates());
            rewrites.add(rewrite);
            markRewrite(trace, rewrite);
            if (!Boolean.TRUE.equals(rewrite.getRewritten())) {
                break;
            }
            currentPlan = copyPlanWithQuery(currentPlan, rewrite.getRewrittenQuery());
        }

        AtomicBoolean contextTruncated = new AtomicBoolean(false);
        List<RetrievedChunk> chunks = evaluation != null && Boolean.TRUE.equals(evaluation.getSufficient())
                ? applyTokenBudget(prompt, finalChunks, contextTruncated)
                : List.of();
        long retrievalCostMs = System.currentTimeMillis() - retrievalStart;
        boolean hit = evaluation != null && Boolean.TRUE.equals(evaluation.getSufficient()) && !chunks.isEmpty();

        if (!hit) {
            MemoryTrace reflectionTrace = memoryAgent.reflectEvidenceFailure(
                    request.getUserId(),
                    request.getSessionId(),
                    prompt,
                    evaluation,
                    trace.size());
            saveMemory(request.getSessionId(), prompt, MISS_ANSWER);
            MemoryTrace afterAnswerTrace = memoryAgent.afterAnswer(request.getUserId(), request.getSessionId(), prompt);
            return AdaptiveRagResponse.builder()
                    .answer(MISS_ANSWER)
                    .citations(List.of())
                    .hit(false)
                    .strategy("ADAPTIVE_" + currentPlan.getStrategy())
                    .rounds(trace.size())
                    .debug(buildDebug(
                            request.getDebug(),
                            currentPlan,
                            trace,
                            System.currentTimeMillis() - start,
                            retrievalCostMs,
                            0L,
                            estimateTokens(prompt),
                            false,
                            buildScoreDetails(finalChunks),
                            evaluation,
                            rewrites,
                            memoryContext,
                            mergeMemoryTrace(memoryTrace, reflectionTrace, afterAnswerTrace)
                    ))
                    .build();
        }

        String userPrompt = buildUserPrompt(prompt, chunks, memoryContext);
        long modelStart = System.currentTimeMillis();
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(
                        SystemMessage.from(buildSystemPrompt()),
                        UserMessage.from(userPrompt)
                )
                .maxOutputTokens(maxOutputTokens)
                .build());
        long modelCostMs = System.currentTimeMillis() - modelStart;
        List<Citation> citations = IntStream.range(0, chunks.size())
                .mapToObj(index -> chunks.get(index).toCitation(index + 1))
                .toList();
        String answer = ensureCitationSection(response.aiMessage().text(), citations);
        saveMemory(request.getSessionId(), prompt, answer);
        MemoryTrace afterAnswerTrace = memoryAgent.afterAnswer(request.getUserId(), request.getSessionId(), prompt);

        log.info("Adaptive RAG | strategy={} | candidates={} | retrieved={} | retrievalCost={}ms | modelCost={}ms",
                currentPlan.getStrategy(), finalCandidates.size(), chunks.size(), retrievalCostMs, modelCostMs);

        return AdaptiveRagResponse.builder()
                .answer(answer)
                .citations(citations)
                .hit(true)
                .strategy("ADAPTIVE_" + currentPlan.getStrategy())
                .rounds(trace.size())
                .debug(buildDebug(
                        request.getDebug(),
                        currentPlan,
                        trace,
                        System.currentTimeMillis() - start,
                        retrievalCostMs,
                        modelCostMs,
                        estimateTokens(buildSystemPrompt() + userPrompt),
                        contextTruncated.get(),
                        buildScoreDetails(chunks),
                        evaluation,
                        rewrites,
                        memoryContext,
                        mergeMemoryTrace(memoryTrace, afterAnswerTrace)
                ))
                .build();
    }

    private AdaptiveRagResponse handleNoRetrieval(Long userId,
                                                  Long sessionId,
                                                  String prompt,
                                                  RetrievalPlan plan,
                                                  MemoryContext memoryContext,
                                                  long start,
                                                  Boolean debug) {
        if (plan.getStrategy() == RetrievalStrategy.FOLLOW_UP_REQUIRED) {
            String answer = "回答：\n请补充错误码、配置项、接口名或更具体的业务对象，我才能准确检索知识库。\n\n引用：\n无";
            saveMemory(sessionId, prompt, answer);
            memoryAgent.afterAnswer(userId, sessionId, prompt);
            return buildNoRetrievalResponse(answer, false, plan, start, "需要追问补充信息。", 0L, 0, Boolean.TRUE.equals(debug));
        }

        long modelStart = System.currentTimeMillis();
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(
                        SystemMessage.from("""
                                你是千言 Agent。当前问题不需要检索企业知识库，请直接简洁回答。
                                输出固定格式：
                                回答：
                                xxx

                                引用：
                                无
                                """),
                        UserMessage.from(buildDirectUserPrompt(prompt, memoryContext))
                )
                .maxOutputTokens(maxOutputTokens)
                .build());
        String answer = ensureNoCitationAnswer(response.aiMessage().text());
        saveMemory(sessionId, prompt, answer);
        memoryAgent.afterAnswer(userId, sessionId, prompt);
        long modelCostMs = System.currentTimeMillis() - modelStart;
        return buildNoRetrievalResponse(answer, true, plan, start, plan.getReason(), modelCostMs, estimateTokens(prompt), Boolean.TRUE.equals(debug));
    }

    private AdaptiveRagResponse buildNoRetrievalResponse(String answer,
                                                         boolean hit,
                                                         RetrievalPlan plan,
                                                         long start,
                                                         String reason,
                                                         long modelCostMs,
                                                         int estimatedInputTokens,
                                                         boolean debugEnabled) {
        AdaptiveRagStep step = AdaptiveRagStep.builder()
                .round(1)
                .query(plan.getQuery())
                .strategy(plan.getStrategy())
                .knowledgeBase(plan.getKnowledgeBase())
                .vectorTopK(0)
                .keywordTopK(0)
                .candidateCount(0)
                .rerankTopK(0)
                .retrievedCount(0)
                .evidenceSufficient(hit)
                .reason(reason)
                .costMs(System.currentTimeMillis() - start)
                .build();
        return AdaptiveRagResponse.builder()
                .answer(answer)
                .citations(List.of())
                .hit(hit)
                .strategy("ADAPTIVE_" + plan.getStrategy())
                .rounds(1)
                .debug(buildDebug(
                        debugEnabled,
                        plan,
                        List.of(step),
                        System.currentTimeMillis() - start,
                        0L,
                        modelCostMs,
                        estimatedInputTokens,
                        false,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null
                ))
                .build();
    }

    private AdaptiveRagDebug buildDebug(Boolean debugEnabled,
                                        RetrievalPlan plan,
                                        List<AdaptiveRagStep> trace,
                                        long totalMs,
                                        long retrievalMs,
                                        long modelMs,
                                        int estimatedInputTokens,
                                        boolean contextTruncated,
                                        List<ScoreDetail> scoreDetails,
                                        EvidenceEvaluation evidenceEvaluation,
                                        List<QueryRewriteResult> queryRewrites,
                                        MemoryContext memoryContext,
                                        MemoryTrace memoryTrace) {
        if (!Boolean.TRUE.equals(debugEnabled)) {
            return null;
        }
        return AdaptiveRagDebug.builder()
                .retrievalPlan(plan)
                .memoryContext(memoryContext)
                .memoryTrace(memoryTrace)
                .adaptiveTrace(trace)
                .evidenceEvaluation(evidenceEvaluation)
                .queryRewrites(queryRewrites)
                .reflection(memoryTrace == null ? null : memoryTrace.getReflection())
                .cost(AdaptiveRagCost.builder()
                        .totalMs(totalMs)
                        .retrievalMs(retrievalMs)
                        .modelMs(modelMs)
                        .build())
                .token(AdaptiveRagToken.builder()
                        .estimatedInputTokens(estimatedInputTokens)
                        .contextTruncated(contextTruncated)
                        .build())
                .scoreDetails(scoreDetails)
                .build();
    }

    private MemoryTrace mergeMemoryTrace(MemoryTrace... traces) {
        MemoryTrace merged = null;
        long costMs = 0;
        for (MemoryTrace trace : traces) {
            if (trace == null) {
                continue;
            }
            costMs += trace.getCostMs() == null ? 0 : trace.getCostMs();
            if (merged == null) {
                merged = trace;
                continue;
            }
            merged = MemoryTrace.builder()
                    .decision(trace.getDecision() == null ? merged.getDecision() : trace.getDecision())
                    .context(merged.getContext() == null ? trace.getContext() : merged.getContext())
                    .summaryRefreshed(Boolean.TRUE.equals(merged.getSummaryRefreshed()) || Boolean.TRUE.equals(trace.getSummaryRefreshed()))
                    .reflection(trace.getReflection() == null ? merged.getReflection() : trace.getReflection())
                    .costMs(costMs)
                    .build();
        }
        return merged;
    }

    private RetrievalRoundResult executeRetrievalRound(String prompt, RetrievalPlan plan, int round) {
        long roundStart = System.currentTimeMillis();
        List<RetrievedChunk> candidates = search(plan);
        List<RetrievedChunk> reranked = rerankService.rerank(plan.getQuery(), candidates, plan.getRerankTopK());
        EvidenceEvaluation evaluation = evidenceEvaluator.evaluate(prompt, plan, reranked);
        AdaptiveRagStep step = AdaptiveRagStep.builder()
                .round(round)
                .query(plan.getQuery())
                .strategy(plan.getStrategy())
                .knowledgeBase(plan.getKnowledgeBase())
                .vectorTopK(plan.getVectorTopK())
                .keywordTopK(plan.getKeywordTopK())
                .candidateCount(candidates.size())
                .rerankTopK(plan.getRerankTopK())
                .retrievedCount(reranked.size())
                .evidenceSufficient(evaluation.getSufficient())
                .topScore(evaluation.getTopScore())
                .coverageScore(evaluation.getCoverageScore())
                .missingAspects(evaluation.getMissingAspects())
                .reason(evaluation.getReason())
                .costMs(System.currentTimeMillis() - roundStart)
                .build();
        return new RetrievalRoundResult(candidates, reranked, evaluation, step);
    }

    private boolean shouldRewrite(int round, EvidenceEvaluation evaluation) {
        return rewriteEnabled
                && round < maxRounds
                && evaluation != null
                && Boolean.TRUE.equals(evaluation.getShouldRewrite());
    }

    private void markRewrite(List<AdaptiveRagStep> trace, QueryRewriteResult rewrite) {
        if (trace.isEmpty()) {
            return;
        }
        AdaptiveRagStep lastStep = trace.get(trace.size() - 1);
        lastStep.setRewritten(rewrite.getRewritten());
        lastStep.setRewriteReason(rewrite.getReason());
    }

    private RetrievalPlan copyPlanWithQuery(RetrievalPlan source, String query) {
        return RetrievalPlan.builder()
                .needRetrieval(source.getNeedRetrieval())
                .knowledgeBase(source.getKnowledgeBase())
                .strategy(source.getStrategy())
                .query(query)
                .vectorTopK(source.getVectorTopK())
                .keywordTopK(source.getKeywordTopK())
                .rerankTopK(source.getRerankTopK())
                .reason(source.getReason())
                .confidence(source.getConfidence())
                .build();
    }

    private List<ScoreDetail> buildScoreDetails(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(chunk -> ScoreDetail.builder()
                        .chunkId(chunk.getChunkId())
                        .fileName(chunk.getFileName())
                        .chunkIndex(chunk.getChunkIndex())
                        .retrievalSource(chunk.getRetrievalSource())
                        .vectorScore(chunk.getVectorScore())
                        .keywordScore(chunk.getKeywordScore())
                        .fusionScore(chunk.getFusionScore())
                        .rerankScore(chunk.getRerankScore())
                        .build())
                .toList();
    }

    private List<RetrievedChunk> search(RetrievalPlan plan) {
        return switch (plan.getStrategy()) {
            case VECTOR -> vectorSearchService.search(plan.getQuery(), plan.getVectorTopK());
            case KEYWORD -> keywordSearchService.search(plan.getQuery(), plan.getKeywordTopK());
            case HYBRID -> searchHybrid(plan);
            default -> hybridSearchService.search(plan.getQuery());
        };
    }

    private List<RetrievedChunk> searchHybrid(RetrievalPlan plan) {
        CompletableFuture<List<RetrievedChunk>> vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearchService.search(plan.getQuery(), plan.getVectorTopK())
        );
        CompletableFuture<List<RetrievedChunk>> keywordFuture = CompletableFuture.supplyAsync(
                () -> keywordSearchService.search(plan.getQuery(), plan.getKeywordTopK())
        );
        return fuse(vectorFuture.join(), keywordFuture.join(), Math.max(plan.getVectorTopK(), plan.getKeywordTopK()));
    }

    private List<RetrievedChunk> fuse(List<RetrievedChunk> vectorChunks, List<RetrievedChunk> keywordChunks, int limit) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        applyRrf(merged, vectorChunks, "vector");
        applyRrf(merged, keywordChunks, "keyword");
        return merged.values().stream()
                .peek(this::fillRetrievalSource)
                .sorted((left, right) -> Double.compare(safeDouble(right.getFusionScore()), safeDouble(left.getFusionScore())))
                .limit(limit)
                .toList();
    }

    private void applyRrf(Map<String, RetrievedChunk> merged, List<RetrievedChunk> chunks, String source) {
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            if (chunk.getChunkId() == null) {
                continue;
            }
            double rrfScore = 1.0 / (RRF_K + i + 1);
            RetrievedChunk current = merged.get(chunk.getChunkId());
            if (current == null) {
                chunk.setFusionScore(rrfScore);
                chunk.setRetrievalSource(source);
                merged.put(chunk.getChunkId(), chunk);
            } else {
                current.mergeFrom(chunk);
                current.setFusionScore(safeDouble(current.getFusionScore()) + rrfScore);
            }
        }
    }

    private void fillRetrievalSource(RetrievedChunk chunk) {
        boolean hasVector = chunk.getVectorScore() != null;
        boolean hasKeyword = chunk.getKeywordScore() != null;
        if (hasVector && hasKeyword) {
            chunk.setRetrievalSource("hybrid");
        } else if (hasVector) {
            chunk.setRetrievalSource("vector");
        } else if (hasKeyword) {
            chunk.setRetrievalSource("keyword");
        }
    }

    private List<RetrievedChunk> applyTokenBudget(String prompt, List<RetrievedChunk> chunks, AtomicBoolean contextTruncated) {
        int promptBudgetChars = Math.max(0, (int) ((maxInputTokens - reservedSystemTokens) * charsPerToken));
        int fixedPromptChars = prompt.length() + 400;
        int contextBudgetChars = Math.max(minChunkChars, promptBudgetChars - fixedPromptChars);
        int totalChars = totalContextChars(chunks);
        if (totalChars <= contextBudgetChars) {
            return chunks;
        }
        contextTruncated.set(true);
        int perChunkBudget = Math.max(minChunkChars, contextBudgetChars / Math.max(1, chunks.size()));
        return chunks.stream()
                .map(chunk -> copyWithText(chunk, truncateByBudget(chunk.getText(), perChunkBudget)))
                .filter(chunk -> chunk.getText() != null && !chunk.getText().isBlank())
                .toList();
    }

    private RetrievedChunk copyWithText(RetrievedChunk source, String text) {
        return RetrievedChunk.builder()
                .embeddingId(source.getEmbeddingId())
                .docId(source.getDocId())
                .chunkId(source.getChunkId())
                .fileName(source.getFileName())
                .chunkIndex(source.getChunkIndex())
                .text(text)
                .vectorScore(source.getVectorScore())
                .keywordScore(source.getKeywordScore())
                .fusionScore(source.getFusionScore())
                .rerankScore(source.getRerankScore())
                .retrievalSource(source.getRetrievalSource())
                .build();
    }

    private String truncateByBudget(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int sentenceEnd = Math.max(
                text.lastIndexOf('。', maxChars),
                Math.max(text.lastIndexOf('\n', maxChars), text.lastIndexOf('；', maxChars))
        );
        int end = sentenceEnd >= minChunkChars ? sentenceEnd + 1 : maxChars;
        return text.substring(0, end) + "...";
    }

    private String buildSystemPrompt() {
        return """
                你是企业知识库问答助手。你必须严格根据提供的知识片段回答问题。
                如果知识片段不足以回答，请明确说明“当前知识库未提供足够信息”。
                必须使用固定格式输出：
                回答：
                xxx

                引用：
                [1] xxx.md 第3段
                [2] xxx.md 第1段
                回答正文中的关键结论可以使用 [1]、[2] 标记引用依据。
                引用列表只能列出本次提供的知识片段编号，不得编造来源。
                """;
    }

    private String buildUserPrompt(String prompt, List<RetrievedChunk> chunks, MemoryContext memoryContext) {
        String context = IntStream.range(0, chunks.size())
                .mapToObj(index -> {
                    RetrievedChunk chunk = chunks.get(index);
                    return String.format("""
                            [%d]
                            文件：%s
                            段落：%s
                            来源：%s
                            内容：%s
                            """,
                            index + 1,
                            chunk.getFileName(),
                            chunk.getChunkIndex(),
                            chunk.getRetrievalSource(),
                            chunk.getText());
                })
                .reduce("", (left, right) -> left + "\n" + right);
        return """
                记忆上下文：
                %s

                用户问题：
                %s

                自适应检索片段：
                %s
                """.formatted(memoryContextText(memoryContext), prompt, context);
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

    private String ensureCitationSection(String answer, List<Citation> citations) {
        String safeAnswer = answer == null || answer.isBlank() ? MISS_ANSWER : answer.strip();
        if (safeAnswer.contains("引用：")) {
            return safeAnswer;
        }
        String citationText = IntStream.range(0, citations.size())
                .mapToObj(index -> {
                    Citation citation = citations.get(index);
                    return String.format("[%d] %s 第%s段", index + 1, citation.getFileName(), citation.getChunkIndex());
                })
                .reduce("", (left, right) -> left + (left.isBlank() ? "" : "\n") + right);
        return safeAnswer + "\n\n引用：\n" + citationText;
    }

    private String ensureNoCitationAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return "回答：\n我暂时没有生成有效回答。\n\n引用：\n无";
        }
        if (answer.contains("回答：") && answer.contains("引用：")) {
            return answer;
        }
        return "回答：\n" + answer.strip() + "\n\n引用：\n无";
    }

    private void saveMemory(Long sessionId, String prompt, String answer) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .id(sessionId == null ? "adaptive-rag-default-session" : sessionId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(memoryMaxMessages)
                .build();
        chatMemory.add(UserMessage.from(prompt));
        chatMemory.add(AiMessage.from(answer));
    }

    private int totalContextChars(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(RetrievedChunk::getText)
                .filter(text -> text != null)
                .mapToInt(String::length)
                .sum();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / charsPerToken);
    }

    private double safeDouble(Double value) {
        return value == null ? 0 : value;
    }

    private String normalize(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }

    private record RetrievalRoundResult(List<RetrievedChunk> candidates,
                                        List<RetrievedChunk> rerankedChunks,
                                        EvidenceEvaluation evaluation,
                                        AdaptiveRagStep step) {
    }
}
