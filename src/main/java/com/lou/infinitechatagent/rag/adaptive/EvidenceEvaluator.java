package com.lou.infinitechatagent.rag.adaptive;

import com.lou.infinitechatagent.rag.adaptive.dto.EvidenceEvaluation;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;
import com.lou.infinitechatagent.rag.dto.RetrievedChunk;

import java.util.List;

public interface EvidenceEvaluator {

    EvidenceEvaluation evaluate(String question, RetrievalPlan plan, List<RetrievedChunk> chunks);
}
