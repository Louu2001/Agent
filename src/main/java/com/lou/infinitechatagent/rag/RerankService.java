package com.lou.infinitechatagent.rag;

import com.lou.infinitechatagent.rag.dto.RetrievedChunk;

import java.util.List;

public interface RerankService {

    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
