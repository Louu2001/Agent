package com.lou.infinitechatagent.rag.adaptive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveRagToken {

    private Integer estimatedInputTokens;

    private Boolean contextTruncated;
}
