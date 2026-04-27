package com.lou.infinitechatagent.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActStep {

    private Integer step;

    private String thought;

    private Boolean needRetrieval;

    private String actionReason;

    private Double confidence;

    private AgentAction action;

    private AgentObservation observation;
}
