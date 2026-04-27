package com.lou.infinitechatagent.agent.governance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolAuditRecord {

    private Long id;

    private Long userId;

    private Long sessionId;

    private String toolName;

    private String actionType;

    private String riskLevel;

    private String decision;

    private String reason;

    private String promptSnippet;

    private LocalDateTime createdAt;
}
