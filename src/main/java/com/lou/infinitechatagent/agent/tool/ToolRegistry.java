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

    private final Map<AgentActionType, AgentTool> toolsByActionType = List.of(
            AgentTool.builder()
                    .name(TOOL_CURRENT_TIME)
                    .actionType(AgentActionType.CURRENT_TIME)
                    .description("查询 Asia/Shanghai 当前日期和时间。")
                    .riskLevel("LOW")
                    .enabled(true)
                    .build(),
            AgentTool.builder()
                    .name(TOOL_HYBRID_SEARCH)
                    .actionType(AgentActionType.HYBRID_SEARCH)
                    .description("调用企业知识库 Hybrid RAG，执行向量检索、关键词检索、RRF 融合、重排序和引用溯源。")
                    .riskLevel("LOW")
                    .enabled(true)
                    .build(),
            AgentTool.builder()
                    .name(TOOL_DIRECT_ANSWER)
                    .actionType(AgentActionType.NO_RETRIEVAL_ANSWER)
                    .description("不调用外部工具或知识库，直接由模型回答通用问题。")
                    .riskLevel("LOW")
                    .enabled(true)
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
