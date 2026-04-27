package com.lou.infinitechatagent.rag;

import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class KeywordSearchService {

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    @Value("${rag.citation.snippet-max-chars:500}")
    private int snippetMaxChars;

    public List<RetrievedChunk> search(String query, int maxResults) {
        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }

        String whereClause = String.join(" or ", keywords.stream()
                .map(keyword -> "(content like ? or file_name like ? or chunk_id like ?)")
                .toList());
        String sql = """
                select chunk_id, doc_id, file_name, chunk_index, content, embedding_id
                from rag_chunk
                where %s
                limit ?
                """.formatted(whereClause);

        Object[] args = buildArgs(keywords, maxResults);
        return ragJdbcTemplate.query(sql, args, (rs, rowNum) -> {
            String content = rs.getString("content");
            return RetrievedChunk.builder()
                    .embeddingId(rs.getString("embedding_id"))
                    .docId(rs.getString("doc_id"))
                    .chunkId(rs.getString("chunk_id"))
                    .fileName(rs.getString("file_name"))
                    .chunkIndex(rs.getInt("chunk_index"))
                    .text(limitText(content))
                    .retrievalSource("keyword")
                    .keywordScore(keywordScore(keywords, content))
                    .build();
        });
    }

    private List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.toLowerCase()
                .replaceAll("[^\\p{IsHan}a-z0-9_\\-.]+", " ");
        return Arrays.stream(normalized.split("\\s+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .distinct()
                .limit(8)
                .toList();
    }

    private Object[] buildArgs(List<String> keywords, int maxResults) {
        Object[] args = new Object[keywords.size() * 3 + 1];
        int index = 0;
        for (String keyword : keywords) {
            String like = "%" + escapeLike(keyword) + "%";
            args[index++] = like;
            args[index++] = like;
            args[index++] = like;
        }
        args[index] = maxResults;
        return args;
    }

    private double keywordScore(List<String> keywords, String text) {
        if (keywords.isEmpty() || text == null) {
            return 0;
        }
        String normalized = text.toLowerCase();
        long hitCount = keywords.stream()
                .filter(normalized::contains)
                .count();
        return (double) hitCount / keywords.size();
    }

    private String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private String limitText(String text) {
        if (text == null || text.length() <= snippetMaxChars) {
            return text;
        }
        return text.substring(0, snippetMaxChars) + "...";
    }
}
