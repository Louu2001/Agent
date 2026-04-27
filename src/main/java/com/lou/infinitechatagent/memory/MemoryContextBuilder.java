package com.lou.infinitechatagent.memory;

import com.lou.infinitechatagent.memory.dto.MemoryContext;
import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.SessionSummary;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class MemoryContextBuilder {

    @Resource
    private SessionSummaryService sessionSummaryService;

    @Resource
    private MemoryRetrievalService memoryRetrievalService;

    @Value("${memory.context.max-summary-chars:800}")
    private int maxSummaryChars;

    public MemoryContext build(Long userId, Long sessionId, String prompt) {
        Optional<SessionSummary> summary = sessionSummaryService.findSummary(userId, sessionId);
        List<MemoryItem> longTermMemories = memoryRetrievalService.retrieveRelevantMemories(userId, prompt);
        return MemoryContext.builder()
                .summaryInjected(summary.isPresent())
                .sessionSummary(summary.map(SessionSummary::getSummary).map(this::limitSummary).orElse(""))
                .longTermMemoryInjected(!longTermMemories.isEmpty())
                .longTermMemories(longTermMemories)
                .usedMemoryCount(longTermMemories.size())
                .estimatedMemoryTokens(estimateMemoryTokens(summary, longTermMemories))
                .build();
    }

    private String limitSummary(String summary) {
        if (summary == null || summary.length() <= maxSummaryChars) {
            return summary;
        }
        return summary.substring(0, maxSummaryChars) + "...";
    }

    private int estimateMemoryTokens(Optional<SessionSummary> summary, List<MemoryItem> memories) {
        int chars = summary.map(SessionSummary::getSummary).map(String::length).orElse(0);
        chars += memories.stream()
                .map(memory -> memory.getSummary() == null || memory.getSummary().isBlank()
                        ? memory.getContent()
                        : memory.getSummary())
                .mapToInt(text -> text == null ? 0 : text.length())
                .sum();
        return (int) Math.ceil(chars / 2.0);
    }
}
