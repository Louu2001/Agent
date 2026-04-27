package com.lou.infinitechatagent.rag.adaptive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalPlan {

    private Boolean needRetrieval;

    private String knowledgeBase;

    private RetrievalStrategy strategy;

    private String query;

    private Integer vectorTopK;

    private Integer keywordTopK;

    private Integer rerankTopK;

    private String reason;

    private Double confidence;
}
