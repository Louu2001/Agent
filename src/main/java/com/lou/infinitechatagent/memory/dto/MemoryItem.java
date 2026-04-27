package com.lou.infinitechatagent.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryItem {

    private String memoryId;

    private Long userId;

    private Long sessionId;

    private MemoryType memoryType;

    private String content;

    private String summary;

    private Double confidence;

    private String source;

    private MemoryStatus status;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
