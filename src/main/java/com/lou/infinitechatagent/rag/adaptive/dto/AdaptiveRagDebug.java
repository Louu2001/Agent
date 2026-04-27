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
public class AdaptiveRagDebug {

    private RetrievalPlan retrievalPlan;

    private List<AdaptiveRagStep> adaptiveTrace;

    private EvidenceEvaluation evidenceEvaluation;

    private List<QueryRewriteResult> queryRewrites;

    private AdaptiveRagCost cost;

    private AdaptiveRagToken token;

    private List<ScoreDetail> scoreDetails;
}
