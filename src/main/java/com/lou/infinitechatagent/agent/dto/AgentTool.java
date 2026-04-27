package com.lou.infinitechatagent.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTool {

    private String name;

    private AgentActionType actionType;

    private String description;

    private String riskLevel;

    private Boolean enabled;
}
