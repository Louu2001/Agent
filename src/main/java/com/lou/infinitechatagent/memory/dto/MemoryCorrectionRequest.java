package com.lou.infinitechatagent.memory.dto;

import lombok.Data;

@Data
public class MemoryCorrectionRequest {

    private Long userId;

    private Long sessionId;

    private MemoryType memoryType;

    private String correctedContent;

    private String correctedSummary;

    private String reason;

    private Double confidence;
}
