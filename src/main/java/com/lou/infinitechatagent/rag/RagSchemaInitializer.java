package com.lou.infinitechatagent.rag;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RagSchemaInitializer {

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    @PostConstruct
    public void initSchema() {
        ragJdbcTemplate.execute("""
                create table if not exists rag_document (
                    id bigint primary key auto_increment,
                    doc_id varchar(64) not null unique,
                    file_name varchar(255) not null,
                    file_path varchar(512),
                    source_type varchar(64),
                    content_hash varchar(64),
                    created_at timestamp default current_timestamp,
                    updated_at timestamp default current_timestamp on update current_timestamp
                ) engine=InnoDB default charset=utf8mb4
                """);

        ragJdbcTemplate.execute("""
                create table if not exists rag_chunk (
                    id bigint primary key auto_increment,
                    chunk_id varchar(64) not null unique,
                    doc_id varchar(64) not null,
                    file_name varchar(255) not null,
                    chunk_index int not null,
                    content text not null,
                    embedding_id varchar(128),
                    created_at timestamp default current_timestamp,
                    key idx_rag_chunk_doc_id (doc_id),
                    key idx_rag_chunk_embedding_id (embedding_id)
                ) engine=InnoDB default charset=utf8mb4
                """);

        addIndexIfMissing("rag_chunk", "idx_rag_chunk_doc_id", "doc_id");
        addIndexIfMissing("rag_chunk", "idx_rag_chunk_embedding_id", "embedding_id");
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
        log.info("RAG - 已创建索引 {}.{}", tableName, indexName);
    }
}
