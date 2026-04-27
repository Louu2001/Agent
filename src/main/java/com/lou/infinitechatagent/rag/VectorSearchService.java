package com.lou.infinitechatagent.rag;

import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectorSearchService {

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Value("${rag.citation.min-score:0.75}")
    private double minScore;

    @Value("${rag.citation.snippet-max-chars:500}")
    private int snippetMaxChars;

    public List<RetrievedChunk> search(String query, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        return result.matches().stream()
                .map(this::toRetrievedChunk)
                .toList();
    }

    private RetrievedChunk toRetrievedChunk(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        String fileName = segment.metadata().getString("file_name");
        String text = cleanSnippet(segment.text(), fileName);

        return RetrievedChunk.builder()
                .embeddingId(match.embeddingId())
                .docId(segment.metadata().getString("doc_id"))
                .chunkId(segment.metadata().getString("chunk_id"))
                .fileName(fileName)
                .chunkIndex(segment.metadata().getInteger("chunk_index"))
                .text(text)
                .retrievalSource("vector")
                .vectorScore(match.score())
                .build();
    }

    private String cleanSnippet(String text, String fileName) {
        if (fileName != null && text != null && text.startsWith(fileName + "\n")) {
            return limitText(text.substring(fileName.length() + 1));
        }
        return limitText(text);
    }

    private String limitText(String text) {
        if (text == null || text.length() <= snippetMaxChars) {
            return text;
        }
        return text.substring(0, snippetMaxChars) + "...";
    }
}
