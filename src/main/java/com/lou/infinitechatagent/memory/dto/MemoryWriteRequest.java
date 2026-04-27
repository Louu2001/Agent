package com.lou.infinitechatagent.memory.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MemoryWriteRequest {

    private Long userId;

    private Long sessionId;

    private MemoryType memoryType;

    private String content;

    private String summary;

    private Double confidence;

    private String source;

    private LocalDateTime expiresAt;
}
