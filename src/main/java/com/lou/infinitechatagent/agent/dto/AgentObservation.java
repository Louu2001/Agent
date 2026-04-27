package com.lou.infinitechatagent.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentObservation {

    private Boolean success;

    private String summary;

    private Integer citationCount;

    private Long costMs;
}
