package com.lou.infinitechatagent.controller;

import com.lou.infinitechatagent.ai.AiChat;
import com.lou.infinitechatagent.monitor.MonitorContext;
import com.lou.infinitechatagent.monitor.MonitorContextHolder;
import com.lou.infinitechatagent.model.dto.ChatRequest;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class AiChatController {

    @Resource
    private AiChat aiChat;

    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest chatRequest) {
        MonitorContextHolder.setContext(MonitorContext.builder().userId(chatRequest.getUserId()).sessionId(chatRequest.getSessionId()).build());
        String chat = aiChat.chat(chatRequest.getSessionId(), chatRequest.getPrompt());
        MonitorContextHolder.clearContext();
        return chat;
    }

    @PostMapping("/streamChat")
    public Flux<String> streamChat(@RequestBody ChatRequest chatRequest) {
        MonitorContext context = MonitorContext.builder()
                .userId(chatRequest.getUserId())
                .sessionId(chatRequest.getSessionId())
                .build();

        return Flux.defer(() -> {
            MonitorContextHolder.setContext(context);
            return aiChat.streamChat(chatRequest.getSessionId(), chatRequest.getPrompt())
                    .doFinally(signal -> MonitorContextHolder.clearContext());
        });
    }
}
