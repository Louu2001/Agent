package com.lou.infinitechatagent.rag;

import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RuleBasedRerankService implements RerankService {

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        Set<String> queryTerms = tokenize(query);
        return candidates.stream()
                .peek(chunk -> chunk.setRerankScore(calculateScore(queryTerms, chunk)))
                .sorted(Comparator.comparing(RetrievedChunk::getRerankScore).reversed())
                .limit(topK)
                .toList();
    }

    private double calculateScore(Set<String> queryTerms, RetrievedChunk chunk) {
        double vectorScore = chunk.getVectorScore() == null ? 0 : chunk.getVectorScore();
        double termScore = keywordOverlapScore(queryTerms, chunk.getText());
        double titleScore = keywordOverlapScore(queryTerms, chunk.getFileName());
        return 0.75 * vectorScore + 0.20 * termScore + 0.05 * titleScore;
    }

    private double keywordOverlapScore(Set<String> queryTerms, String text) {
        if (queryTerms.isEmpty() || text == null || text.isBlank()) {
            return 0;
        }
        String normalizedText = text.toLowerCase();
        long hitCount = queryTerms.stream()
                .filter(normalizedText::contains)
                .count();
        return (double) hitCount / queryTerms.size();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.toLowerCase()
                .replaceAll("[^\\p{IsHan}a-z0-9_\\-.]+", " ");
        return Arrays.stream(normalized.split("\\s+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toSet());
    }
}
