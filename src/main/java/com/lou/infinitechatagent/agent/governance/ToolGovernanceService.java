package com.lou.infinitechatagent.agent.governance;

import com.lou.infinitechatagent.agent.dto.AgentAction;
import com.lou.infinitechatagent.agent.dto.AgentTool;
import com.lou.infinitechatagent.agent.governance.dto.ToolAuditRecord;
import com.lou.infinitechatagent.agent.governance.dto.ToolGovernanceDecision;
import com.lou.infinitechatagent.agent.tool.ToolRegistry;
import com.lou.infinitechatagent.guardrail.InputSafetyService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ToolGovernanceService {

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    @Value("${agent.tool-governance.enabled:true}")
    private boolean governanceEnabled;

    @Value("${agent.tool-governance.confirmation-threshold:HIGH}")
    private String confirmationThreshold;

    @Value("${agent.tool-governance.prompt-injection-check.enabled:true}")
    private boolean promptInjectionCheckEnabled;

    private final InputSafetyService inputSafetyService = new InputSafetyService();

    public ToolGovernanceDecision evaluate(Long userId,
                                           Long sessionId,
                                           String prompt,
                                           AgentAction action,
                                           Set<String> confirmedTools) {
        if (!governanceEnabled) {
            ToolGovernanceDecision decision = allow(action, "工具治理未启用，仅记录放行。");
            audit(userId, sessionId, prompt, decision);
            return decision;
        }
        if (action == null || action.getType() == null) {
            ToolGovernanceDecision decision = block(null, "缺少可执行工具动作。", List.of("ACTION_EMPTY"));
            audit(userId, sessionId, prompt, decision);
            return decision;
        }
        AgentTool tool = toolRegistry.findByActionType(action.getType()).orElse(null);
        if (tool == null) {
            ToolGovernanceDecision decision = ToolGovernanceDecision.builder()
                    .allowed(false)
                    .confirmationRequired(false)
                    .toolName(action.getToolName())
                    .actionType(action.getType().name())
                    .riskLevel("UNKNOWN")
                    .reason("工具未注册或已被禁用。")
                    .guardrailHits(List.of("TOOL_DISABLED_OR_UNREGISTERED"))
                    .build();
            audit(userId, sessionId, prompt, decision);
            return decision;
        }

        List<String> guardrailHits = promptInjectionCheckEnabled
                ? inputSafetyService.detectPromptInjection(prompt)
                : List.of();
        if (!guardrailHits.isEmpty()) {
            ToolGovernanceDecision decision = ToolGovernanceDecision.builder()
                    .allowed(false)
                    .confirmationRequired(false)
                    .toolName(tool.getName())
                    .actionType(tool.getActionType().name())
                    .riskLevel(tool.getRiskLevel())
                    .reason("检测到疑似 Prompt Injection，已拒绝工具调用。")
                    .guardrailHits(guardrailHits)
                    .build();
            audit(userId, sessionId, prompt, decision);
            return decision;
        }

        ToolRiskLevel riskLevel = ToolRiskLevel.from(tool.getRiskLevel());
        ToolRiskLevel threshold = ToolRiskLevel.from(confirmationThreshold);
        boolean confirmationRequired = riskLevel.gte(threshold);
        boolean confirmed = confirmedTools != null && confirmedTools.contains(tool.getName());
        if (confirmationRequired && !confirmed) {
            ToolGovernanceDecision decision = ToolGovernanceDecision.builder()
                    .allowed(false)
                    .confirmationRequired(true)
                    .toolName(tool.getName())
                    .actionType(tool.getActionType().name())
                    .riskLevel(tool.getRiskLevel())
                    .reason("该工具风险等级为 " + tool.getRiskLevel() + "，需要用户确认后才能执行。")
                    .guardrailHits(List.of("CONFIRMATION_REQUIRED"))
                    .build();
            audit(userId, sessionId, prompt, decision);
            return decision;
        }

        ToolGovernanceDecision decision = ToolGovernanceDecision.builder()
                .allowed(true)
                .confirmationRequired(false)
                .toolName(tool.getName())
                .actionType(tool.getActionType().name())
                .riskLevel(tool.getRiskLevel())
                .reason("工具通过启用状态、风险等级和护轨检查。")
                .guardrailHits(List.of())
                .build();
        audit(userId, sessionId, prompt, decision);
        return decision;
    }

    public List<ToolAuditRecord> listAuditRecords(Long userId, Long sessionId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        if (userId != null && sessionId != null) {
            return ragJdbcTemplate.query("""
                    select id, user_id, session_id, tool_name, action_type, risk_level, decision, reason, prompt_snippet, created_at
                    from agent_tool_audit
                    where user_id = ? and session_id = ?
                    order by id desc
                    limit ?
                    """, this::mapAuditRecord, userId, sessionId, safeLimit);
        }
        if (userId != null) {
            return ragJdbcTemplate.query("""
                    select id, user_id, session_id, tool_name, action_type, risk_level, decision, reason, prompt_snippet, created_at
                    from agent_tool_audit
                    where user_id = ?
                    order by id desc
                    limit ?
                    """, this::mapAuditRecord, userId, safeLimit);
        }
        return ragJdbcTemplate.query("""
                select id, user_id, session_id, tool_name, action_type, risk_level, decision, reason, prompt_snippet, created_at
                from agent_tool_audit
                order by id desc
                limit ?
                """, this::mapAuditRecord, safeLimit);
    }

    private ToolGovernanceDecision allow(AgentAction action, String reason) {
        return ToolGovernanceDecision.builder()
                .allowed(true)
                .confirmationRequired(false)
                .toolName(action == null ? null : action.getToolName())
                .actionType(action == null || action.getType() == null ? null : action.getType().name())
                .riskLevel("UNKNOWN")
                .reason(reason)
                .guardrailHits(List.of())
                .build();
    }

    private ToolGovernanceDecision block(AgentAction action, String reason, List<String> guardrailHits) {
        return ToolGovernanceDecision.builder()
                .allowed(false)
                .confirmationRequired(false)
                .toolName(action == null ? null : action.getToolName())
                .actionType(action == null || action.getType() == null ? null : action.getType().name())
                .riskLevel("UNKNOWN")
                .reason(reason)
                .guardrailHits(guardrailHits)
                .build();
    }

    private void audit(Long userId, Long sessionId, String prompt, ToolGovernanceDecision decision) {
        ragJdbcTemplate.update("""
                insert into agent_tool_audit (
                    user_id, session_id, tool_name, action_type, risk_level, decision, reason, prompt_snippet, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                userId,
                sessionId,
                safeValue(decision.getToolName(), "unknown"),
                safeValue(decision.getActionType(), "unknown"),
                safeValue(decision.getRiskLevel(), "UNKNOWN"),
                Boolean.TRUE.equals(decision.getAllowed()) ? "ALLOWED" : "BLOCKED",
                truncate(decision.getReason(), 512),
                truncate(prompt, 512));
    }

    private ToolAuditRecord mapAuditRecord(ResultSet rs, int rowNum) throws SQLException {
        return ToolAuditRecord.builder()
                .id(rs.getLong("id"))
                .userId(readNullableLong(rs, "user_id"))
                .sessionId(readNullableLong(rs, "session_id"))
                .toolName(rs.getString("tool_name"))
                .actionType(rs.getString("action_type"))
                .riskLevel(rs.getString("risk_level"))
                .decision(rs.getString("decision"))
                .reason(rs.getString("reason"))
                .promptSnippet(rs.getString("prompt_snippet"))
                .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                .build();
    }

    private Long readNullableLong(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String safeValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }
}
