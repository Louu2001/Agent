package com.lou.infinitechatagent.agent.dto;

import lombok.Data;

@Data
public class AgentRequest {

    private Long userId;

    private Long sessionId;

    private String prompt;

    private Boolean debug;

    private java.util.Set<String> confirmedTools;
}
