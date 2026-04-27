package com.lou.infinitechatagent.memory;

import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.MemoryType;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class MemoryRetrievalService {

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Value("${memory.context.max-memory-items:5}")
    private int maxMemoryItems;

    @Value("${memory.context.max-memory-chars:1200}")
    private int maxMemoryChars;

    @Value("${memory.context.min-relevance-score:0.08}")
    private double minRelevanceScore;

    public List<MemoryItem> retrieveRelevantMemories(Long userId, String prompt) {
        List<MemoryItem> candidates = longTermMemoryService.findActiveByUser(userId, null, Math.max(maxMemoryItems * 4, 10));
        if (candidates.isEmpty()) {
            return List.of();
        }
        String normalizedPrompt = normalize(prompt);
        Set<String> queryTerms = tokenize(normalizedPrompt);
        List<ScoredMemory> scoredMemories = candidates.stream()
                .map(memory -> new ScoredMemory(memory, score(memory, normalizedPrompt, queryTerms)))
                .filter(scored -> scored.score >= minRelevanceScore || queryTerms.isEmpty())
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed()
                        .thenComparing(scored -> safeConfidence(scored.memory()), Comparator.reverseOrder()))
                .toList();
        return applyBudget(scoredMemories);
    }

    private List<MemoryItem> applyBudget(List<ScoredMemory> scoredMemories) {
        List<MemoryItem> selected = new ArrayList<>();
        int charBudget = Math.max(200, maxMemoryChars);
        int usedChars = 0;
        for (ScoredMemory scoredMemory : scoredMemories) {
            if (selected.size() >= maxMemoryItems) {
                break;
            }
            int memoryChars = memoryText(scoredMemory.memory()).length();
            if (!selected.isEmpty() && usedChars + memoryChars > charBudget) {
                continue;
            }
            selected.add(scoredMemory.memory());
            usedChars += memoryChars;
        }
        return selected;
    }

    private double score(MemoryItem memory, String normalizedPrompt, Set<String> queryTerms) {
        String memoryText = normalize(memoryText(memory));
        double score = safeConfidence(memory) * 0.15;
        if (StringUtils.hasText(normalizedPrompt) && memoryText.contains(normalizedPrompt)) {
            score += 0.6;
        }
        if (!queryTerms.isEmpty()) {
            Set<String> memoryTerms = tokenize(memoryText);
            long hitCount = queryTerms.stream().filter(memoryTerms::contains).count();
            score += (double) hitCount / queryTerms.size();
        }
        score += typeBoost(memory.getMemoryType(), normalizedPrompt);
        return score;
    }

    private double typeBoost(MemoryType memoryType, String normalizedPrompt) {
        if (memoryType == null || !StringUtils.hasText(normalizedPrompt)) {
            return 0;
        }
        return switch (memoryType) {
            case TECH_STACK -> containsAny(normalizedPrompt, "技术栈", "java", "spring", "redis", "mysql", "langchain4j") ? 0.3 : 0;
            case OUTPUT_STYLE -> containsAny(normalizedPrompt, "格式", "文档", "postman", "简历", "输出", "怎么写") ? 0.25 : 0;
            case PROJECT_CONTEXT -> containsAny(normalizedPrompt, "项目", "agent", "rag", "react", "memory") ? 0.25 : 0;
            case USER_PREFERENCE -> containsAny(normalizedPrompt, "偏好", "习惯", "喜欢", "希望") ? 0.2 : 0;
            case REFLECTION -> containsAny(normalizedPrompt, "失败", "优化", "为什么", "问题", "策略") ? 0.2 : 0;
            case IMPORTANT_FACT -> 0.05;
        };
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return terms;
        }
        for (String term : text.split("[\\s,，。.!！?？:：;；/\\\\|()（）\\[\\]{}<>《》\"'`]+")) {
            String normalized = normalize(term);
            if (normalized.length() >= 2) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private String memoryText(MemoryItem memory) {
        if (memory == null) {
            return "";
        }
        String summary = memory.getSummary();
        return StringUtils.hasText(summary) ? summary : memory.getContent();
    }

    private String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }

    private double safeConfidence(MemoryItem memory) {
        return memory.getConfidence() == null ? 0.8 : memory.getConfidence();
    }

    private record ScoredMemory(MemoryItem memory, double score) {
    }
}
