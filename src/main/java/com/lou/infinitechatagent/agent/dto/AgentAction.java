package com.lou.infinitechatagent.agent.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAction {

    private AgentActionType type;

    private String toolName;

    private String query;

    private Map<String, Object> arguments;
}
