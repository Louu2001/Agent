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
public class QueryRewriteResult {

    private Boolean rewritten;

    private String originalQuery;

    private String rewrittenQuery;

    private String reason;

    private List<String> addedTerms;
}
