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

    private String sectionTitle;

    private String headingPath;

    private String chunkType;

    private Integer charCount;

    private Integer tokenEstimate;

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
                .sectionTitle(sectionTitle)
                .headingPath(headingPath)
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
        if (this.sectionTitle == null) {
            this.sectionTitle = other.getSectionTitle();
        }
        if (this.headingPath == null) {
            this.headingPath = other.getHeadingPath();
        }
        if (this.chunkType == null) {
            this.chunkType = other.getChunkType();
        }
        if (this.charCount == null) {
            this.charCount = other.getCharCount();
        }
        if (this.tokenEstimate == null) {
            this.tokenEstimate = other.getTokenEstimate();
        }
    }
}
