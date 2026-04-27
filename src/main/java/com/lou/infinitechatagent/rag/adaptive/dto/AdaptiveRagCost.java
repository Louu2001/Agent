package com.lou.infinitechatagent.rag.adaptive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveRagCost {

    private Long totalMs;

    private Long retrievalMs;

    private Long modelMs;
}
