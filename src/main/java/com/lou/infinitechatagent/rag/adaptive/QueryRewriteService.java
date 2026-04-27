package com.lou.infinitechatagent.rag.adaptive;

import com.lou.infinitechatagent.rag.adaptive.dto.EvidenceEvaluation;
import com.lou.infinitechatagent.rag.adaptive.dto.QueryRewriteResult;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;
import com.lou.infinitechatagent.rag.dto.RetrievedChunk;

import java.util.List;

public interface QueryRewriteService {

    QueryRewriteResult rewrite(String question,
                               RetrievalPlan plan,
                               EvidenceEvaluation evaluation,
                               List<RetrievedChunk> previousChunks);
}
