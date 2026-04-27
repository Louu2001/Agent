package com.lou.infinitechatagent.memory.dto;

import lombok.Data;

@Data
public class MemoryAgentRequest {

    private Long userId;

    private Long sessionId;

    private String prompt;
}
