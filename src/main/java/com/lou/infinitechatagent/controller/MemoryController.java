package com.lou.infinitechatagent.controller;

import com.lou.infinitechatagent.memory.LongTermMemoryService;
import com.lou.infinitechatagent.memory.MemoryAgent;
import com.lou.infinitechatagent.memory.MemoryContextBuilder;
import com.lou.infinitechatagent.memory.ReflectiveMemoryService;
import com.lou.infinitechatagent.memory.SessionSummaryService;
import com.lou.infinitechatagent.memory.dto.MemoryContext;
import com.lou.infinitechatagent.memory.dto.MemoryContextRequest;
import com.lou.infinitechatagent.memory.dto.MemoryAgentRequest;
import com.lou.infinitechatagent.memory.dto.MemoryCorrectionRequest;
import com.lou.infinitechatagent.memory.dto.MemoryCorrectionResult;
import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.MemoryTrace;
import com.lou.infinitechatagent.memory.dto.MemoryType;
import com.lou.infinitechatagent.memory.dto.MemoryWriteRequest;
import com.lou.infinitechatagent.memory.dto.ReflectionRequest;
import com.lou.infinitechatagent.memory.dto.ReflectionResult;
import com.lou.infinitechatagent.memory.dto.SessionSummary;
import com.lou.infinitechatagent.memory.dto.SessionSummaryRequest;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/memory")
public class MemoryController {

    @Resource
    private SessionSummaryService sessionSummaryService;

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Resource
    private MemoryContextBuilder memoryContextBuilder;

    @Resource
    private ReflectiveMemoryService reflectiveMemoryService;

    @Resource
    private MemoryAgent memoryAgent;

    @GetMapping("/session/summary")
    public SessionSummary getSessionSummary(@RequestParam Long userId, @RequestParam Long sessionId) {
        return sessionSummaryService.findSummary(userId, sessionId)
                .orElse(SessionSummary.builder()
                        .userId(userId)
                        .sessionId(sessionId)
                        .summary("")
                        .turnCount(0)
                        .build());
    }

    @PostMapping("/session/summarize")
    public SessionSummary summarize(@RequestBody SessionSummaryRequest request) {
        return sessionSummaryService.refreshNow(request.getUserId(), request.getSessionId());
    }

    @GetMapping("/context")
    public MemoryContext getMemoryContext(@RequestParam Long userId,
                                          @RequestParam Long sessionId,
                                          @RequestParam(required = false) String prompt) {
        return memoryContextBuilder.build(userId, sessionId, prompt);
    }

    @PostMapping("/context")
    public MemoryContext buildMemoryContext(@RequestBody MemoryContextRequest request) {
        return memoryContextBuilder.build(request.getUserId(), request.getSessionId(), request.getPrompt());
    }

    @PostMapping("/write")
    public MemoryItem writeMemory(@RequestBody MemoryWriteRequest request) {
        return longTermMemoryService.write(request);
    }

    @PostMapping("/correct")
    public MemoryCorrectionResult correctMemory(@RequestBody MemoryCorrectionRequest request) {
        MemoryType memoryType = request.getMemoryType() == null ? MemoryType.IMPORTANT_FACT : request.getMemoryType();
        List<String> disabledMemoryIds = longTermMemoryService.disableActiveByType(request.getUserId(), memoryType);
        MemoryItem correctedMemory = longTermMemoryService.correct(request);
        return MemoryCorrectionResult.builder()
                .correctedMemory(correctedMemory)
                .disabledMemoryIds(disabledMemoryIds)
                .reason(request.getReason())
                .build();
    }

    @GetMapping("/user/{userId}")
    public List<MemoryItem> listUserMemories(@PathVariable Long userId,
                                             @RequestParam(required = false) MemoryType memoryType,
                                             @RequestParam(defaultValue = "10") int limit) {
        return longTermMemoryService.findActiveByUser(userId, memoryType, limit);
    }

    @GetMapping("/item/{memoryId}")
    public MemoryItem getMemory(@PathVariable String memoryId) {
        return longTermMemoryService.findByMemoryId(memoryId)
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在：" + memoryId));
    }

    @PostMapping("/disable/{memoryId}")
    public Boolean disableMemory(@PathVariable String memoryId) {
        return longTermMemoryService.disable(memoryId);
    }

    @PostMapping("/reflection")
    public ReflectionResult writeReflection(@RequestBody ReflectionRequest request) {
        return reflectiveMemoryService.reflect(request);
    }

    @PostMapping("/agent/context")
    public MemoryTrace buildAgentMemoryContext(@RequestBody MemoryAgentRequest request) {
        return memoryAgent.readContext(request.getUserId(), request.getSessionId(), request.getPrompt());
    }
}
