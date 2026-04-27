package com.lou.infinitechatagent.rag;

import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class HybridSearchService {

    private static final int RRF_K = 60;

    @Resource
    private VectorSearchService vectorSearchService;

    @Resource
    private KeywordSearchService keywordSearchService;

    @Value("${rag.retrieval.vector-results:20}")
    private int vectorResults;

    @Value("${rag.retrieval.keyword-results:20}")
    private int keywordResults;

    @Value("${rag.retrieval.candidate-results:20}")
    private int candidateResults;

    public List<RetrievedChunk> search(String query) {
        long start = System.currentTimeMillis();
        CompletableFuture<List<RetrievedChunk>> vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearchService.search(query, vectorResults)
        );
        CompletableFuture<List<RetrievedChunk>> keywordFuture = CompletableFuture.supplyAsync(
                () -> keywordSearchService.search(query, keywordResults)
        );

        List<RetrievedChunk> vectorChunks = vectorFuture.join();
        List<RetrievedChunk> keywordChunks = keywordFuture.join();
        List<RetrievedChunk> fused = fuse(vectorChunks, keywordChunks);

        log.info("RAG Hybrid Search | vector={} | keyword={} | fused={} | cost={}ms",
                vectorChunks.size(), keywordChunks.size(), fused.size(), System.currentTimeMillis() - start);
        return fused;
    }

    private List<RetrievedChunk> fuse(List<RetrievedChunk> vectorChunks, List<RetrievedChunk> keywordChunks) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        applyRrf(merged, vectorChunks, "vector");
        applyRrf(merged, keywordChunks, "keyword");

        return merged.values().stream()
                .peek(this::fillRetrievalSource)
                .sorted((left, right) -> Double.compare(
                        safeDouble(right.getFusionScore()),
                        safeDouble(left.getFusionScore())
                ))
                .limit(candidateResults)
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

    private double safeDouble(Double value) {
        return value == null ? 0 : value;
    }
}
