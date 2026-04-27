package com.lou.infinitechatagent.rag.adaptive;

import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagRequest;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;

public interface RetrievalPlanner {

    RetrievalPlan plan(AdaptiveRagRequest request);
}
