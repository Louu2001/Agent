package com.lou.infinitechatagent.controller;

import com.lou.infinitechatagent.Monitor.MonitorContext;
import com.lou.infinitechatagent.Monitor.MonitorContextHolder;
import com.lou.infinitechatagent.agent.ReActAgentOrchestrator;
import com.lou.infinitechatagent.agent.dto.AgentRequest;
import com.lou.infinitechatagent.agent.dto.AgentResponse;
import com.lou.infinitechatagent.agent.dto.AgentTool;
import com.lou.infinitechatagent.agent.tool.ToolRegistry;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent")
public class AgentController {

    @Resource
    private ReActAgentOrchestrator reActAgentOrchestrator;

    @Resource
    private ToolRegistry toolRegistry;

    @GetMapping("/tools")
    public List<AgentTool> tools() {
        return toolRegistry.listEnabledTools();
    }

    @PostMapping("/chat")
    public AgentResponse chat(@RequestBody AgentRequest request) {
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId(request.getUserId())
                .sessionId(request.getSessionId())
                .build());
        try {
            return reActAgentOrchestrator.chat(request);
        } finally {
            MonitorContextHolder.clearContext();
        }
    }
}
