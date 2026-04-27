package com.lou.infinitechatagent.memory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MemorySchemaInitializer {

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    @PostConstruct
    public void initSchema() {
        ragJdbcTemplate.execute("""
                create table if not exists session_summary (
                    id bigint primary key auto_increment,
                    user_id bigint not null,
                    session_id bigint not null,
                    summary text not null,
                    turn_count int not null default 0,
                    last_message_at datetime null,
                    created_at timestamp default current_timestamp,
                    updated_at timestamp default current_timestamp on update current_timestamp,
                    unique key uk_session_summary (user_id, session_id)
                ) engine=InnoDB default charset=utf8mb4
                """);

        ragJdbcTemplate.execute("""
                create table if not exists agent_memory (
                    id bigint primary key auto_increment,
                    memory_id varchar(128) not null unique,
                    user_id bigint not null,
                    session_id bigint null,
                    memory_type varchar(64) not null,
                    content text not null,
                    summary varchar(512) null,
                    confidence double not null default 0.8,
                    source varchar(64) not null,
                    status varchar(32) not null default 'ACTIVE',
                    expires_at datetime null,
                    created_at timestamp default current_timestamp,
                    updated_at timestamp default current_timestamp on update current_timestamp
                ) engine=InnoDB default charset=utf8mb4
                """);

        addIndexIfMissing("session_summary", "idx_session_summary_user", "user_id");
        addIndexIfMissing("session_summary", "idx_session_summary_session", "session_id");
        addIndexIfMissing("agent_memory", "idx_agent_memory_user_type", "user_id, memory_type");
        addIndexIfMissing("agent_memory", "idx_agent_memory_session", "session_id");
        addIndexIfMissing("agent_memory", "idx_agent_memory_status", "status");
    }

    private void addIndexIfMissing(String tableName, String indexName, String columnName) {
        Integer count = ragJdbcTemplate.queryForObject("""
                select count(1)
                from information_schema.statistics
                where table_schema = database()
                  and table_name = ?
                  and index_name = ?
                """, Integer.class, tableName, indexName);
        if (count != null && count > 0) {
            return;
        }
        ragJdbcTemplate.execute("create index " + indexName + " on " + tableName + "(" + columnName + ")");
        log.info("Memory - 已创建索引 {}.{}", tableName, indexName);
    }
}
