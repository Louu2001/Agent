package com.lou.infinitechatagent.memory;

import com.lou.infinitechatagent.memory.dto.MemoryContext;
import com.lou.infinitechatagent.memory.dto.MemoryDecision;
import com.lou.infinitechatagent.memory.dto.MemoryTrace;
import com.lou.infinitechatagent.memory.dto.ReflectionResult;
import com.lou.infinitechatagent.rag.adaptive.dto.EvidenceEvaluation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MemoryAgent {

    @Resource
    private RuleBasedMemoryPlanner memoryPlanner;

    @Resource
    private MemoryContextBuilder memoryContextBuilder;

    @Resource
    private SessionSummaryService sessionSummaryService;

    @Resource
    private ReflectiveMemoryService reflectiveMemoryService;

    public MemoryTrace readContext(Long userId, Long sessionId, String prompt) {
        long start = System.currentTimeMillis();
        MemoryDecision decision = memoryPlanner.plan(userId, sessionId, prompt);
        MemoryContext context = Boolean.TRUE.equals(decision.getNeedReadMemory())
                ? memoryContextBuilder.build(userId, sessionId, prompt)
                : MemoryContext.builder()
                .summaryInjected(false)
                .sessionSummary("")
                .longTermMemoryInjected(false)
                .longTermMemories(java.util.List.of())
                .usedMemoryCount(0)
                .estimatedMemoryTokens(0)
                .build();
        return MemoryTrace.builder()
                .decision(decision)
                .context(context)
                .summaryRefreshed(false)
                .costMs(System.currentTimeMillis() - start)
                .build();
    }

    public MemoryTrace afterAnswer(Long userId, Long sessionId, String prompt) {
        long start = System.currentTimeMillis();
        MemoryDecision decision = memoryPlanner.plan(userId, sessionId, prompt);
        boolean refreshed = false;
        if (Boolean.TRUE.equals(decision.getNeedWriteSummary())) {
            sessionSummaryService.refreshIfNeeded(userId, sessionId);
            refreshed = true;
        }
        return MemoryTrace.builder()
                .decision(decision)
                .summaryRefreshed(refreshed)
                .costMs(System.currentTimeMillis() - start)
                .build();
    }

    public MemoryTrace reflectEvidenceFailure(Long userId,
                                              Long sessionId,
                                              String prompt,
                                              EvidenceEvaluation evaluation,
                                              int rounds) {
        long start = System.currentTimeMillis();
        MemoryDecision decision = memoryPlanner.plan(userId, sessionId, prompt);
        decision.setNeedWriteReflection(true);
        ReflectionResult reflection = reflectiveMemoryService.reflectEvidenceFailure(userId, sessionId, prompt, evaluation, rounds);
        log.info("MemoryAgent | reflectionWritten={} | reason={}",
                reflection.getWritten(), reflection.getReason());
        return MemoryTrace.builder()
                .decision(decision)
                .reflection(reflection)
                .costMs(System.currentTimeMillis() - start)
                .build();
    }
}
