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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class LongTermMemoryService {

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    public MemoryItem write(MemoryWriteRequest request) {
        return writeWithDedup(request);
    }

    public MemoryItem writeWithDedup(MemoryWriteRequest request) {
        validateWriteRequest(request);
        MemoryType memoryType = request.getMemoryType() == null ? MemoryType.IMPORTANT_FACT : request.getMemoryType();
        Optional<MemoryItem> similarMemory = findMostSimilarMemory(request.getUserId(), memoryType, request.getContent());
        if (similarMemory.isPresent()) {
            return mergeMemory(similarMemory.get(), request);
        }
        return insertMemory(request, memoryType);
    }

    public MemoryItem correct(com.lou.infinitechatagent.memory.dto.MemoryCorrectionRequest request) {
        if (request == null || request.getUserId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!StringUtils.hasText(request.getCorrectedContent())) {
            throw new IllegalArgumentException("correctedContent 不能为空");
        }
        MemoryType memoryType = request.getMemoryType() == null ? MemoryType.IMPORTANT_FACT : request.getMemoryType();
        List<MemoryItem> candidates = findActiveByUser(request.getUserId(), memoryType, 20);
        candidates.stream()
                .map(MemoryItem::getMemoryId)
                .forEach(this::disable);

        MemoryWriteRequest writeRequest = new MemoryWriteRequest();
        writeRequest.setUserId(request.getUserId());
        writeRequest.setSessionId(request.getSessionId());
        writeRequest.setMemoryType(memoryType);
        writeRequest.setContent(request.getCorrectedContent());
        writeRequest.setSummary(request.getCorrectedSummary());
        writeRequest.setConfidence(request.getConfidence() == null ? 0.95 : request.getConfidence());
        writeRequest.setSource("correction");
        return insertMemory(writeRequest, memoryType);
    }

    public List<String> disableActiveByType(Long userId, MemoryType memoryType) {
        if (userId == null || memoryType == null) {
            return List.of();
        }
        List<String> memoryIds = findActiveByUser(userId, memoryType, 20).stream()
                .map(MemoryItem::getMemoryId)
                .toList();
        memoryIds.forEach(this::disable);
        return memoryIds;
    }

    private MemoryItem insertMemory(MemoryWriteRequest request, MemoryType memoryType) {
        String memoryId = "mem_" + UUID.randomUUID().toString().replace("-", "");
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

    private Optional<MemoryItem> findMostSimilarMemory(Long userId, MemoryType memoryType, String content) {
        if (userId == null || memoryType == null || !StringUtils.hasText(content)) {
            return Optional.empty();
        }
        return findActiveByUser(userId, memoryType, 20).stream()
                .map(memory -> new SimilarMemory(memory, similarity(memoryText(memory), content)))
                .filter(similar -> similar.score() >= 0.72)
                .max((left, right) -> Double.compare(left.score(), right.score()))
                .map(SimilarMemory::memory);
    }

    private MemoryItem mergeMemory(MemoryItem existing, MemoryWriteRequest request) {
        String mergedContent = mergeText(existing.getContent(), request.getContent());
        String mergedSummary = StringUtils.hasText(request.getSummary())
                ? request.getSummary().strip()
                : existing.getSummary();
        double mergedConfidence = Math.max(
                existing.getConfidence() == null ? 0.8 : existing.getConfidence(),
                request.getConfidence() == null ? 0.8 : request.getConfidence()
        );
        String source = StringUtils.hasText(request.getSource())
                ? "merged:" + request.getSource().strip()
                : "merged";
        ragJdbcTemplate.update("""
                update agent_memory
                set session_id = coalesce(?, session_id),
                    content = ?,
                    summary = ?,
                    confidence = ?,
                    source = ?,
                    expires_at = ?,
                    updated_at = now()
                where memory_id = ?
                """,
                request.getSessionId(),
                mergedContent,
                normalizeBlank(mergedSummary),
                mergedConfidence,
                source,
                toTimestamp(request.getExpiresAt()),
                existing.getMemoryId());
        return findByMemoryId(existing.getMemoryId())
                .orElseThrow(() -> new IllegalStateException("长期记忆合并失败"));
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

    private String mergeText(String existing, String incoming) {
        if (!StringUtils.hasText(existing)) {
            return incoming == null ? "" : incoming.strip();
        }
        if (!StringUtils.hasText(incoming)) {
            return existing.strip();
        }
        String safeExisting = existing.strip();
        String safeIncoming = incoming.strip();
        if (safeExisting.contains(safeIncoming)) {
            return safeExisting;
        }
        if (safeIncoming.contains(safeExisting)) {
            return safeIncoming;
        }
        return safeExisting + "\n补充：" + safeIncoming;
    }

    private String memoryText(MemoryItem memory) {
        if (memory == null) {
            return "";
        }
        return (StringUtils.hasText(memory.getSummary()) ? memory.getSummary() : memory.getContent());
    }

    private double similarity(String left, String right) {
        Set<String> leftTokens = tokenize(left);
        Set<String> rightTokens = tokenize(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return tokens;
        }
        for (String token : text.toLowerCase().split("[\\s,，。.!！?？:：;；/\\\\|()（）\\[\\]{}<>《》\"'`+-]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record SimilarMemory(MemoryItem memory, double score) {
    }
}
