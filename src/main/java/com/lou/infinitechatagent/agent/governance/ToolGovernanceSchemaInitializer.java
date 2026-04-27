package com.lou.infinitechatagent.agent.governance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ToolGovernanceSchemaInitializer {

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    @PostConstruct
    public void initSchema() {
        ragJdbcTemplate.execute("""
                create table if not exists agent_tool_audit (
                    id bigint primary key auto_increment,
                    user_id bigint null,
                    session_id bigint null,
                    tool_name varchar(128) not null,
                    action_type varchar(64) not null,
                    risk_level varchar(32) not null,
                    decision varchar(32) not null,
                    reason varchar(512) null,
                    prompt_snippet varchar(512) null,
                    created_at timestamp default current_timestamp,
                    key idx_tool_audit_user_session (user_id, session_id),
                    key idx_tool_audit_tool (tool_name),
                    key idx_tool_audit_decision (decision)
                ) engine=InnoDB default charset=utf8mb4
                """);
        log.info("Tool Governance - agent_tool_audit schema ready");
    }
}
