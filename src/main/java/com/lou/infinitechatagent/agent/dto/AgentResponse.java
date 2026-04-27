package com.lou.infinitechatagent.agent.dto;

import java.util.List;

import com.lou.infinitechatagent.agent.governance.dto.ToolGovernanceDecision;
import com.lou.infinitechatagent.rag.dto.Citation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private String answer;

    private AgentActionType finalAction;

    private String strategy;

    private List<Citation> citations;

    private List<ReActStep> reactTrace;

    private Long costMs;

    private Long modelCostMs;

    private Long retrievalCostMs;

    private Integer estimatedInputTokens;

    private Boolean contextTruncated;

    private ToolGovernanceDecision toolGovernance;
}
