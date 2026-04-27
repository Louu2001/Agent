package com.lou.infinitechatagent.memory;

import com.lou.infinitechatagent.memory.dto.SessionSummary;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SessionSummaryService {

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatModel chatModel;

    @Value("${memory.summary.trigger-turns:6}")
    private int triggerTurns;

    @Value("${memory.summary.window-messages:30}")
    private int windowMessages;

    @Value("${memory.summary.max-output-tokens:500}")
    private int maxOutputTokens;

    public Optional<SessionSummary> findSummary(Long userId, Long sessionId) {
        if (userId == null || sessionId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(ragJdbcTemplate.queryForObject("""
                    select user_id, session_id, summary, turn_count, last_message_at, created_at, updated_at
                    from session_summary
                    where user_id = ? and session_id = ?
                    """, (rs, rowNum) -> SessionSummary.builder()
                    .userId(rs.getLong("user_id"))
                    .sessionId(rs.getLong("session_id"))
                    .summary(rs.getString("summary"))
                    .turnCount(rs.getInt("turn_count"))
                    .lastMessageAt(toLocalDateTime(rs.getTimestamp("last_message_at")))
                    .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                    .updatedAt(toLocalDateTime(rs.getTimestamp("updated_at")))
                    .build(), userId, sessionId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public void refreshIfNeeded(Long userId, Long sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        List<ChatMessage> messages = loadMessages(sessionId);
        if (messages.isEmpty()) {
            return;
        }
        int messageCount = messages.size();
        int previousCount = findSummary(userId, sessionId)
                .map(SessionSummary::getTurnCount)
                .orElse(0);
        if (messageCount - previousCount < triggerTurns) {
            return;
        }
        refreshNow(userId, sessionId);
    }

    public SessionSummary refreshNow(Long userId, Long sessionId) {
        List<ChatMessage> messages = loadMessages(sessionId);
        if (messages.isEmpty()) {
            throw new IllegalStateException("当前 session 暂无可总结的对话记忆");
        }
        String oldSummary = findSummary(userId, sessionId)
                .map(SessionSummary::getSummary)
                .orElse("暂无历史摘要。");
        String summary = summarize(oldSummary, messages);
        upsertSummary(userId, sessionId, summary, messages.size());
        return findSummary(userId, sessionId)
                .orElseThrow(() -> new IllegalStateException("摘要写入失败"));
    }

    private List<ChatMessage> loadMessages(Long sessionId) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .id(sessionId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(windowMessages)
                .build();
        return chatMemory.messages();
    }

    private String summarize(String oldSummary, List<ChatMessage> messages) {
        String conversation = messages.stream()
                .map(ChatMessage::toString)
                .collect(Collectors.joining("\n"));
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(
                        dev.langchain4j.data.message.SystemMessage.from("""
                                你是会话摘要助手。请把对话压缩为长期上下文摘要。
                                必须保留：
                                1. 用户当前目标
                                2. 已完成事项
                                3. 未完成事项
                                4. 技术约束
                                5. 用户偏好
                                输出中文，简洁，避免编造。
                                """),
                        dev.langchain4j.data.message.UserMessage.from("""
                                旧摘要：
                                %s

                                最近对话：
                                %s
                                """.formatted(oldSummary, conversation))
                )
                .maxOutputTokens(maxOutputTokens)
                .build());
        return response.aiMessage().text();
    }

    private void upsertSummary(Long userId, Long sessionId, String summary, int turnCount) {
        ragJdbcTemplate.update("""
                insert into session_summary (user_id, session_id, summary, turn_count, last_message_at, created_at, updated_at)
                values (?, ?, ?, ?, now(), now(), now())
                on duplicate key update
                    summary = values(summary),
                    turn_count = values(turn_count),
                    last_message_at = values(last_message_at),
                    updated_at = now()
                """, userId, sessionId, summary, turnCount);
        log.info("Memory - 已更新 session summary | userId={} | sessionId={} | turnCount={}", userId, sessionId, turnCount);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
