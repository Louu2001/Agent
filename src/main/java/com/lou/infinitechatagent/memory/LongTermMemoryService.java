package com.lou.infinitechatagent.memory;

import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.MemoryStatus;
import com.lou.infinitechatagent.memory.dto.MemoryType;
import com.lou.infinitechatagent.memory.dto.MemoryWriteRequest;
import jakarta.annotation.Resource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class LongTermMemoryService {

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    public MemoryItem write(MemoryWriteRequest request) {
        validateWriteRequest(request);
        String memoryId = "mem_" + UUID.randomUUID().toString().replace("-", "");
        MemoryType memoryType = request.getMemoryType() == null ? MemoryType.IMPORTANT_FACT : request.getMemoryType();
        double confidence = request.getConfidence() == null ? 0.8 : request.getConfidence();
        String source = StringUtils.hasText(request.getSource()) ? request.getSource() : "manual";
        ragJdbcTemplate.update("""
                insert into agent_memory (
                    memory_id, user_id, session_id, memory_type, content, summary,
                    confidence, source, status, expires_at, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """,
                memoryId,
                request.getUserId(),
                request.getSessionId(),
                memoryType.name(),
                request.getContent().strip(),
                normalizeBlank(request.getSummary()),
                confidence,
                source,
                MemoryStatus.ACTIVE.name(),
                toTimestamp(request.getExpiresAt()));
        return findByMemoryId(memoryId)
                .orElseThrow(() -> new IllegalStateException("长期记忆写入失败"));
    }

    public Optional<MemoryItem> findByMemoryId(String memoryId) {
        if (!StringUtils.hasText(memoryId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(ragJdbcTemplate.queryForObject("""
                    select memory_id, user_id, session_id, memory_type, content, summary, confidence,
                           source, status, expires_at, created_at, updated_at
                    from agent_memory
                    where memory_id = ?
                    """, this::mapMemoryItem, memoryId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public List<MemoryItem> findActiveByUser(Long userId, MemoryType memoryType, int limit) {
        if (userId == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 20));
        if (memoryType == null) {
            return ragJdbcTemplate.query("""
                    select memory_id, user_id, session_id, memory_type, content, summary, confidence,
                           source, status, expires_at, created_at, updated_at
                    from agent_memory
                    where user_id = ?
                      and status = ?
                      and (expires_at is null or expires_at > now())
                    order by confidence desc, updated_at desc
                    limit ?
                    """, this::mapMemoryItem, userId, MemoryStatus.ACTIVE.name(), safeLimit);
        }
        return ragJdbcTemplate.query("""
                select memory_id, user_id, session_id, memory_type, content, summary, confidence,
                       source, status, expires_at, created_at, updated_at
                from agent_memory
                where user_id = ?
                  and memory_type = ?
                  and status = ?
                  and (expires_at is null or expires_at > now())
                order by confidence desc, updated_at desc
                limit ?
                """, this::mapMemoryItem, userId, memoryType.name(), MemoryStatus.ACTIVE.name(), safeLimit);
    }

    public boolean disable(String memoryId) {
        if (!StringUtils.hasText(memoryId)) {
            return false;
        }
        int rows = ragJdbcTemplate.update("""
                update agent_memory
                set status = ?, updated_at = now()
                where memory_id = ? and status = ?
                """, MemoryStatus.DISABLED.name(), memoryId, MemoryStatus.ACTIVE.name());
        return rows > 0;
    }

    private void validateWriteRequest(MemoryWriteRequest request) {
        if (request == null || request.getUserId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!StringUtils.hasText(request.getContent())) {
            throw new IllegalArgumentException("content 不能为空");
        }
    }

    private MemoryItem mapMemoryItem(ResultSet rs, int rowNum) throws SQLException {
        return MemoryItem.builder()
                .memoryId(rs.getString("memory_id"))
                .userId(rs.getLong("user_id"))
                .sessionId(readNullableLong(rs, "session_id"))
                .memoryType(MemoryType.valueOf(rs.getString("memory_type")))
                .content(rs.getString("content"))
                .summary(rs.getString("summary"))
                .confidence(rs.getDouble("confidence"))
                .source(rs.getString("source"))
                .status(MemoryStatus.valueOf(rs.getString("status")))
                .expiresAt(toLocalDateTime(rs.getTimestamp("expires_at")))
                .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                .updatedAt(toLocalDateTime(rs.getTimestamp("updated_at")))
                .build();
    }

    private Long readNullableLong(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }

    private String normalizeBlank(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
