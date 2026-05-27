package com.lou.infinitechatagent.agent.context;

import com.lou.infinitechatagent.memory.MemoryAgent;
import com.lou.infinitechatagent.memory.dto.MemoryContext;
import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.MemoryTrace;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentContextManager {

    private static final String DEFAULT_MEMORY_ID = "agent-default-session";

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private MemoryAgent memoryAgent;

    @Value("${agent.react.memory-max-messages:20}")
    private int memoryMaxMessages;

    @Value("${agent.react.context.max-history-chars:2000}")
    private int maxHistoryChars;

    @Value("${agent.react.context.max-input-tokens:1800}")
    private int maxInputTokens;

    @Value("${agent.react.context.reserved-system-tokens:400}")
    private int reservedSystemTokens;

    @Value("${agent.react.context.chars-per-token:2.0}")
    private double charsPerToken;

    public AgentContext prepare(Long userId, Long sessionId, String prompt) {
        MemoryTrace memoryTrace = memoryAgent.readContext(userId, sessionId, prompt);
        MemoryContext memoryContext = memoryTrace.getContext();
        String memoryText = memoryContextText(memoryContext);
        HistoryWindow historyWindow = compactHistory(loadMessages(sessionId));
        int estimatedTokens = estimateTokens(prompt + "\n" + memoryText + "\n" + historyWindow.text());
        boolean tokenBudgetExceeded = estimatedTokens > Math.max(1, maxInputTokens - reservedSystemTokens);
        return AgentContext.builder()
                .prompt(prompt)
                .memoryTrace(memoryTrace)
                .memoryContext(memoryContext)
                .memoryText(memoryText)
                .historyText(historyWindow.text())
                .historyCompacted(historyWindow.compacted())
                .contextTruncated(historyWindow.compacted() || tokenBudgetExceeded)
                .estimatedInputTokens(estimatedTokens)
                .build();
    }

    public void saveTurn(Long sessionId, String prompt, String answer) {
        MessageWindowChatMemory chatMemory = buildChatMemory(sessionId);
        chatMemory.add(UserMessage.from(prompt));
        chatMemory.add(AiMessage.from(answer));
    }

    public MemoryTrace afterAnswer(Long userId, Long sessionId, String prompt) {
        return memoryAgent.afterAnswer(userId, sessionId, prompt);
    }

    public String buildDirectPrompt(String prompt, AgentContext context) {
        return """
                记忆上下文：
                %s

                最近对话：
                %s

                用户问题：
                %s
                """.formatted(
                blankAsNone(context.getMemoryText()),
                blankAsNone(context.getHistoryText()),
                prompt);
    }

    private List<ChatMessage> loadMessages(Long sessionId) {
        return buildChatMemory(sessionId).messages();
    }

    private MessageWindowChatMemory buildChatMemory(Long sessionId) {
        Object memoryId = sessionId == null ? DEFAULT_MEMORY_ID : sessionId;
        return MessageWindowChatMemory.builder()
                .id(memoryId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(memoryMaxMessages)
                .build();
    }

    private HistoryWindow compactHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new HistoryWindow("无", false);
        }
        List<String> formatted = messages.stream()
                .map(this::formatHistoryMessage)
                .filter(StringUtils::hasText)
                .toList();
        if (formatted.isEmpty()) {
            return new HistoryWindow("无", false);
        }
        List<String> selected = new ArrayList<>();
        int usedChars = 0;
        boolean compacted = false;
        for (int i = formatted.size() - 1; i >= 0; i--) {
            String item = formatted.get(i);
            int nextChars = usedChars + item.length() + 1;
            if (!selected.isEmpty() && nextChars > maxHistoryChars) {
                compacted = true;
                break;
            }
            selected.add(0, item);
            usedChars = nextChars;
        }
        if (selected.size() < formatted.size()) {
            compacted = true;
        }
        String text = String.join("\n", selected);
        if (compacted) {
            text = "历史对话已压缩，仅保留最近相关窗口。\n" + text;
        }
        return new HistoryWindow(text, compacted);
    }

    private String formatHistoryMessage(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return "用户：" + userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            return "助手：" + aiMessage.text();
        }
        return null;
    }

    private String memoryContextText(MemoryContext memoryContext) {
        if (memoryContext == null
                || (!Boolean.TRUE.equals(memoryContext.getSummaryInjected())
                && !Boolean.TRUE.equals(memoryContext.getLongTermMemoryInjected()))) {
            return "暂无。";
        }
        StringBuilder builder = new StringBuilder();
        if (Boolean.TRUE.equals(memoryContext.getSummaryInjected())) {
            builder.append("会话摘要：\n")
                    .append(memoryContext.getSessionSummary())
                    .append("\n");
        }
        if (Boolean.TRUE.equals(memoryContext.getLongTermMemoryInjected())) {
            builder.append("长期记忆：\n");
            for (MemoryItem memory : memoryContext.getLongTermMemories()) {
                builder.append("- [")
                        .append(memory.getMemoryType())
                        .append("] ")
                        .append(StringUtils.hasText(memory.getSummary()) ? memory.getSummary() : memory.getContent())
                        .append("\n");
            }
        }
        return builder.toString().strip();
    }

    private String blankAsNone(String text) {
        return StringUtils.hasText(text) ? text : "无";
    }

    private int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return (int) Math.ceil(text.length() / charsPerToken);
    }

    private record HistoryWindow(String text, boolean compacted) {
    }
}
