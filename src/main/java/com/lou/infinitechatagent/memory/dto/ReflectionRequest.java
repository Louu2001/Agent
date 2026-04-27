package com.lou.infinitechatagent.memory.dto;

import lombok.Data;

import java.util.List;

@Data
public class ReflectionRequest {

    private Long userId;

    private Long sessionId;

    private ReflectionTrigger trigger;

    private String prompt;

    private String answer;

    private String reason;

    private List<String> missingAspects;

    private Double confidence;
}
