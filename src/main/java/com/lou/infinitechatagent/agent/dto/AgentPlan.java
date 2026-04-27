package com.lou.infinitechatagent.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlan {

    private String thought;

    private AgentAction action;

    private Boolean needRetrieval;

    private String actionReason;

    private Double confidence;

    private String plannerType;
}
