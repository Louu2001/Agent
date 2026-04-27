package com.lou.infinitechatagent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {

    private String embeddingId;

    private String docId;

    private String chunkId;

    private String fileName;

    private Integer chunkIndex;

    private String text;

    private String retrievalSource;

    private Double vectorScore;

    private Double keywordScore;

    private Double fusionScore;

    private Double rerankScore;

    public Citation toCitation(int index) {
        return Citation.builder()
                .index(index)
                .docId(docId)
                .chunkId(chunkId)
                .fileName(fileName)
                .chunkIndex(chunkIndex)
                .snippet(text)
                .retrievalSource(retrievalSource)
                .vectorScore(vectorScore)
                .keywordScore(keywordScore)
                .fusionScore(fusionScore)
                .rerankScore(rerankScore)
                .build();
    }

    public void mergeFrom(RetrievedChunk other) {
        if (other == null) {
            return;
        }
        if (other.getVectorScore() != null) {
            this.vectorScore = other.getVectorScore();
        }
        if (other.getKeywordScore() != null) {
            this.keywordScore = other.getKeywordScore();
        }
        if (this.text == null || this.text.isBlank()) {
            this.text = other.getText();
        }
        if (this.embeddingId == null) {
            this.embeddingId = other.getEmbeddingId();
        }
    }
}
