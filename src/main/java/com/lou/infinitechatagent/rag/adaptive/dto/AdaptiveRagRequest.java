package com.lou.infinitechatagent.rag.adaptive.dto;

import lombok.Data;

@Data
public class AdaptiveRagRequest {

    private Long userId;

    private Long sessionId;

    private String prompt;

    private Boolean debug;
}
