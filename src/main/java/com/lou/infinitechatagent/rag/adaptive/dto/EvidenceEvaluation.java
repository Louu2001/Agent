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
public class EvidenceEvaluation {

    private Boolean sufficient;

    private Double topScore;

    private Double coverageScore;

    private Integer citationCount;

    private List<String> missingAspects;

    private Boolean shouldRewrite;

    private Boolean shouldAskFollowUp;

    private String reason;
}
