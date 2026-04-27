package com.lou.infinitechatagent.rag.adaptive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveRagStep {

    private Integer round;

    private String query;

    private RetrievalStrategy strategy;

    private String knowledgeBase;

    private Integer vectorTopK;

    private Integer keywordTopK;

    private Integer candidateCount;

    private Integer rerankTopK;

    private Integer retrievedCount;

    private Boolean evidenceSufficient;

    private String reason;

    private Long costMs;
}
