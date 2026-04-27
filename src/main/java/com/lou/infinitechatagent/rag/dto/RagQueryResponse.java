package com.lou.infinitechatagent.rag.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryResponse {

    private String answer;

    private List<Citation> citations;

    private Boolean hit;

    private Integer retrievedCount;

    private Integer candidateCount;

    private Long costMs;

    private Long retrievalCostMs;

    private Long modelCostMs;

    private Integer promptChars;

    private Integer contextChars;

    private Integer estimatedInputTokens;

    private Boolean contextTruncated;
}
