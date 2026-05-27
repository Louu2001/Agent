package com.lou.infinitechatagent.agent.tool;

import com.lou.infinitechatagent.agent.dto.AgentActionType;
import com.lou.infinitechatagent.agent.dto.AgentTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private static final String TOOL_CURRENT_TIME = "current_time";
    private static final String TOOL_HYBRID_SEARCH = "hybrid_search";
    private static final String TOOL_DIRECT_ANSWER = "direct_answer";
    private static final String TOOL_MEMORY_WRITE = "memory_write";
    private static final String TOOL_MEMORY_SEARCH = "memory_search";
    private static final String TOOL_EMAIL_SEND = "email_send";
    private static final String TOOL_WEB_SEARCH = "web_search";

    private final Map<AgentActionType, AgentTool> toolsByActionType = List.of(
            AgentTool.builder()
                    .name(TOOL_CURRENT_TIME)
                    .actionType(AgentActionType.CURRENT_TIME)
                    .description("查询 Asia/Shanghai 当前日期和时间。")
                    .riskLevel("LOW")
                    .enabled(true)
                    .confirmationRequired(false)
                    .build(),
            AgentTool.builder()
                    .name(TOOL_HYBRID_SEARCH)
                    .actionType(AgentActionType.HYBRID_SEARCH)
                    .description("调用企业知识库 Hybrid RAG，执行向量检索、关键词检索、RRF 融合、重排序和引用溯源。")
                    .riskLevel("MEDIUM")
                    .enabled(true)
                    .confirmationRequired(false)
                    .build(),
            AgentTool.builder()
                    .name(TOOL_DIRECT_ANSWER)
                    .actionType(AgentActionType.NO_RETRIEVAL_ANSWER)
                    .description("不调用外部工具或知识库，直接由模型回答通用问题。")
                    .riskLevel("LOW")
                    .enabled(true)
                    .confirmationRequired(false)
                    .build(),
            AgentTool.builder()
                    .name(TOOL_MEMORY_WRITE)
                    .actionType(AgentActionType.MEMORY_WRITE)
                    .description("把用户明确要求记住的偏好、项目背景、技术栈或重要事实写入长期记忆。")
                    .riskLevel("MEDIUM")
                    .enabled(true)
                    .confirmationRequired(false)
                    .build(),
            AgentTool.builder()
                    .name(TOOL_MEMORY_SEARCH)
                    .actionType(AgentActionType.MEMORY_SEARCH)
                    .description("查询用户长期记忆，用于回答用户之前说过的偏好、项目背景、技术栈或重要事实。")
                    .riskLevel("LOW")
                    .enabled(true)
                    .confirmationRequired(false)
                    .build(),
            AgentTool.builder()
                    .name(TOOL_EMAIL_SEND)
                    .actionType(AgentActionType.EMAIL_SEND)
                    .description("向指定邮箱发送邮件。该工具会产生外部副作用，必须经过高风险工具确认。")
                    .riskLevel("HIGH")
                    .enabled(true)
                    .confirmationRequired(true)
                    .build(),
            AgentTool.builder()
                    .name(TOOL_WEB_SEARCH)
                    .actionType(AgentActionType.WEB_SEARCH)
                    .description("调用联网搜索服务查询最新或外部公开信息。")
                    .riskLevel("MEDIUM")
                    .enabled(true)
                    .confirmationRequired(false)
                    .build()
    ).stream().collect(Collectors.toUnmodifiableMap(AgentTool::getActionType, Function.identity()));

    public List<AgentTool> listEnabledTools() {
        return toolsByActionType.values().stream()
                .filter(tool -> Boolean.TRUE.equals(tool.getEnabled()))
                .toList();
    }

    public Optional<AgentTool> findByActionType(AgentActionType actionType) {
        return Optional.ofNullable(toolsByActionType.get(actionType))
                .filter(tool -> Boolean.TRUE.equals(tool.getEnabled()));
    }

    public AgentTool requireTool(AgentActionType actionType) {
        return findByActionType(actionType)
                .orElseThrow(() -> new IllegalStateException("Tool is disabled or not registered: " + actionType));
    }
}
