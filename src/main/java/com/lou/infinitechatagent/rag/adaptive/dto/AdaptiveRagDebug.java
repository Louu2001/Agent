package com.lou.infinitechatagent.rag.adaptive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import com.lou.infinitechatagent.memory.dto.MemoryContext;
import com.lou.infinitechatagent.memory.dto.MemoryTrace;
import com.lou.infinitechatagent.memory.dto.ReflectionResult;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveRagDebug {

    private RetrievalPlan retrievalPlan;

    private MemoryContext memoryContext;

    private MemoryTrace memoryTrace;

    private List<AdaptiveRagStep> adaptiveTrace;

    private EvidenceEvaluation evidenceEvaluation;

    private List<QueryRewriteResult> queryRewrites;

    private ReflectionResult reflection;

    private AdaptiveRagCost cost;

    private AdaptiveRagToken token;

    private List<ScoreDetail> scoreDetails;
}
