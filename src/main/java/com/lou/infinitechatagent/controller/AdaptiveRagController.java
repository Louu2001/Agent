package com.lou.infinitechatagent.controller;

import com.lou.infinitechatagent.monitor.MonitorContext;
import com.lou.infinitechatagent.monitor.MonitorContextHolder;
import com.lou.infinitechatagent.rag.adaptive.AdaptiveRagOrchestrator;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagRequest;
import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagResponse;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag/adaptive")
public class AdaptiveRagController {

    @Resource
    private AdaptiveRagOrchestrator adaptiveRagOrchestrator;

    @PostMapping("/chat")
    public AdaptiveRagResponse chat(@RequestBody AdaptiveRagRequest request) {
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId(request.getUserId())
                .sessionId(request.getSessionId())
                .build());
        try {
            return adaptiveRagOrchestrator.chat(request);
        } finally {
            MonitorContextHolder.clearContext();
        }
    }
}
