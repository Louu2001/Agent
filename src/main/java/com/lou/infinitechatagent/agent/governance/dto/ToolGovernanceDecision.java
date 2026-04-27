package com.lou.infinitechatagent.agent.governance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolGovernanceDecision {

    private Boolean allowed;

    private Boolean confirmationRequired;

    private String toolName;

    private String actionType;

    private String riskLevel;

    private String reason;

    private List<String> guardrailHits;
}
