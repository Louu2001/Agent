package com.lou.infinitechatagent.controller;

import com.lou.infinitechatagent.Monitor.MonitorContext;
import com.lou.infinitechatagent.Monitor.MonitorContextHolder;
import com.lou.infinitechatagent.model.dto.ChatRequest;
import com.lou.infinitechatagent.rag.RagQueryService;
import com.lou.infinitechatagent.rag.dto.RagQueryResponse;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag")
public class RagChatController {

    @Resource
    private RagQueryService ragQueryService;

    @PostMapping("/chat")
    public RagQueryResponse chatWithCitations(@RequestBody ChatRequest chatRequest) {
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId(chatRequest.getUserId())
                .sessionId(chatRequest.getSessionId())
                .build());
        try {
            return ragQueryService.chatWithCitations(chatRequest.getSessionId(), chatRequest.getPrompt());
        } finally {
            MonitorContextHolder.clearContext();
        }
    }
}
