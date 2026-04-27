package com.lou.infinitechatagent.rag.adaptive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    private Double topScore;

    private Double coverageScore;

    private List<String> missingAspects;

    private Boolean rewritten;

    private String rewriteReason;

    private String reason;

    private Long costMs;
}
