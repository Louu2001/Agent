package com.lou.infinitechatagent.rag;

import com.lou.infinitechatagent.rag.dto.Citation;
import com.lou.infinitechatagent.rag.dto.RagQueryResponse;
import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

@Service
@Slf4j
public class RagQueryService {

    private static final String MISS_ANSWER = "当前知识库未提供足够信息，无法回答该问题。";

    @Resource
    private ChatModel chatModel;

    @Resource
    private RerankService rerankService;

    @Resource
    private HybridSearchService hybridSearchService;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rerank.top-k:5}")
    private int rerankTopK;

    @Value("${rag.citation.max-output-tokens:500}")
    private int maxOutputTokens;

    @Value("${rag.token.max-input-tokens:1800}")
    private int maxInputTokens;

    @Value("${rag.token.reserved-system-tokens:300}")
    private int reservedSystemTokens;

    @Value("${rag.token.min-chunk-chars:180}")
    private int minChunkChars;

    @Value("${rag.token.chars-per-token:2.0}")
    private double charsPerToken;

    public RagQueryResponse chatWithCitations(Long sessionId, String prompt) {
        long start = System.currentTimeMillis();
        MessageWindowChatMemory chatMemory = buildChatMemory(sessionId);
        List<ChatMessage> historyMessages = chatMemory.messages();
        long retrievalStart = System.currentTimeMillis();
        List<RetrievedChunk> candidates = hybridSearchService.search(prompt);
        List<RetrievedChunk> chunks = rerankEnabled
                ? rerankService.rerank(prompt, candidates, rerankTopK)
                : candidates.stream().limit(rerankTopK).toList();
        AtomicBoolean contextTruncated = new AtomicBoolean(false);
        List<RetrievedChunk> budgetedChunks = applyTokenBudget(prompt, chunks, contextTruncated);
        long retrievalCostMs = System.currentTimeMillis() - retrievalStart;
        log.info("RAG Rerank | enabled={} | before={} | after={}", rerankEnabled, candidates.size(), chunks.size());

        if (budgetedChunks.isEmpty()) {
            chatMemory.add(UserMessage.from(prompt));
            chatMemory.add(AiMessage.from(MISS_ANSWER));
            return RagQueryResponse.builder()
                    .answer(MISS_ANSWER)
                    .citations(List.of())
                    .hit(false)
                    .retrievedCount(0)
                    .candidateCount(candidates.size())
                    .costMs(System.currentTimeMillis() - start)
                    .retrievalCostMs(retrievalCostMs)
                    .modelCostMs(0L)
                    .promptChars(prompt.length())
                    .contextChars(0)
                    .estimatedInputTokens(estimateTokens(prompt))
                    .contextTruncated(false)
                    .build();
        }

        String userPrompt = buildUserPrompt(prompt, budgetedChunks, historyMessages);
        long modelStart = System.currentTimeMillis();
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(
                        SystemMessage.from(buildSystemPrompt()),
                        UserMessage.from(userPrompt)
                )
                .maxOutputTokens(maxOutputTokens)
                .build());
        long modelCostMs = System.currentTimeMillis() - modelStart;

        List<Citation> citations = budgetedChunks.stream()
                .map(chunk -> chunk.toCitation(budgetedChunks.indexOf(chunk) + 1))
                .toList();

        String answer = ensureCitationSection(response.aiMessage().text(), citations);
        chatMemory.add(UserMessage.from(prompt));
        chatMemory.add(AiMessage.from(answer));
        return RagQueryResponse.builder()
                .answer(answer)
                .citations(citations)
                .hit(true)
                .retrievedCount(budgetedChunks.size())
                .candidateCount(candidates.size())
                .costMs(System.currentTimeMillis() - start)
                .retrievalCostMs(retrievalCostMs)
                .modelCostMs(modelCostMs)
                .promptChars(userPrompt.length())
                .contextChars(totalContextChars(budgetedChunks))
                .estimatedInputTokens(estimateTokens(buildSystemPrompt() + userPrompt))
                .contextTruncated(contextTruncated.get())
                .build();
    }

    private List<RetrievedChunk> applyTokenBudget(String prompt, List<RetrievedChunk> chunks, AtomicBoolean contextTruncated) {
        int promptBudgetChars = Math.max(0, (int) ((maxInputTokens - reservedSystemTokens) * charsPerToken));
        int fixedPromptChars = prompt.length() + 400;
        int contextBudgetChars = Math.max(minChunkChars, promptBudgetChars - fixedPromptChars);

        List<RetrievedChunk> selected = chunks.stream()
                .map(chunk -> copyWithText(chunk, chunk.getText()))
                .toList();

        int totalChars = totalContextChars(selected);
        if (totalChars <= contextBudgetChars) {
            return selected;
        }

        contextTruncated.set(true);
        int perChunkBudget = Math.max(minChunkChars, contextBudgetChars / Math.max(1, selected.size()));
        return selected.stream()
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
                不得编造未出现在知识片段中的事实。
                """;
    }

    private MessageWindowChatMemory buildChatMemory(Long sessionId) {
        Object memoryId = sessionId == null ? "rag-default-session" : sessionId;
        return MessageWindowChatMemory.builder()
                .id(memoryId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(20)
                .build();
    }

    private String buildUserPrompt(String prompt, List<RetrievedChunk> chunks, List<ChatMessage> historyMessages) {
        String history = buildHistory(historyMessages);
        String context = IntStream.range(0, chunks.size())
                .mapToObj(index -> {
                    RetrievedChunk chunk = chunks.get(index);
                    return String.format("""
                        [%d]
                        文件：%s
                        段落：%s
                        来源：%s
                        向量分：%.4f
                        关键词分：%.4f
                        融合分：%.4f
                        重排分：%.4f
                        内容：%s
                        """,
                        index + 1,
                        chunk.getFileName(),
                        chunk.getChunkIndex(),
                        chunk.getRetrievalSource(),
                        safeDouble(chunk.getVectorScore()),
                        safeDouble(chunk.getKeywordScore()),
                        safeDouble(chunk.getFusionScore()),
                        safeDouble(chunk.getRerankScore()),
                        chunk.getText());
                })
                .reduce("", (left, right) -> left + "\n" + right);

        return String.format("""
                历史对话：
                %s

                用户问题：
                %s

                知识片段：
                %s

                请基于上述知识片段回答，严格按如下格式输出：

                回答：
                这里写答案正文，关键结论后用 [1]、[2] 标注来源。

                引用：
                [1] 文件名 第x段
                [2] 文件名 第y段
                """, history, prompt, context);
    }

    private String buildHistory(List<ChatMessage> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return "无";
        }
        return historyMessages.stream()
                .map(this::formatHistoryMessage)
                .filter(text -> text != null && !text.isBlank())
                .reduce("", (left, right) -> left + "\n" + right)
                .trim();
    }

    private String formatHistoryMessage(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return "用户：" + userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            return "助手：" + aiMessage.text();
        }
        return null;
    }

    private String ensureCitationSection(String answer, List<Citation> citations) {
        if (answer != null && answer.contains("引用：")) {
            return answer;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("回答：\n");
        builder.append(answer == null ? "" : answer.trim());
        builder.append("\n\n引用：\n");
        for (Citation citation : citations) {
            builder.append("[")
                    .append(citation.getIndex())
                    .append("] ")
                    .append(citation.getFileName())
                    .append(" 第")
                    .append(citation.getChunkIndex())
                    .append("段\n");
        }
        return builder.toString().trim();
    }

    private double safeDouble(Double value) {
        return value == null ? 0 : value;
    }
}
