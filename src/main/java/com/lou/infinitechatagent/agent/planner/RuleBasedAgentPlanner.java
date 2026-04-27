package com.lou.infinitechatagent.agent.planner;

import com.lou.infinitechatagent.agent.dto.AgentAction;
import com.lou.infinitechatagent.agent.dto.AgentActionType;
import com.lou.infinitechatagent.agent.dto.AgentPlan;
import com.lou.infinitechatagent.agent.dto.AgentTool;
import com.lou.infinitechatagent.agent.tool.ToolRegistry;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class RuleBasedAgentPlanner implements AgentPlanner {

    private static final Pattern CODE_OR_IDENTIFIER_PATTERN = Pattern.compile(
            "([A-Z]{2,}-\\d+)|([a-zA-Z][a-zA-Z0-9_]+\\.[a-zA-Z][a-zA-Z0-9_]+)|(@[A-Za-z]+)"
    );

    @Resource
    private ToolRegistry toolRegistry;

    @Override
    public AgentPlan plan(String prompt) {
        if (prompt.isBlank()) {
            return buildPlan(
                    AgentActionType.NO_RETRIEVAL_ANSWER,
                    prompt,
                    false,
                    "用户输入为空，无法触发工具或知识库检索。",
                    "empty_prompt",
                    0.99
            );
        }
        if (isCurrentTimeQuestion(prompt)) {
            return buildPlan(
                    AgentActionType.CURRENT_TIME,
                    prompt,
                    false,
                    "问题询问实时日期或时间，调用时间工具比检索知识库更准确。",
                    "time_question",
                    0.95
            );
        }
        if (shouldRetrieve(prompt)) {
            return buildPlan(
                    AgentActionType.HYBRID_SEARCH,
                    prompt,
                    true,
                    "问题包含企业知识、错误码、配置项、接口名或引用诉求，需要检索知识库。",
                    "knowledge_or_identifier_matched",
                    0.86
            );
        }
        return buildPlan(
                AgentActionType.NO_RETRIEVAL_ANSWER,
                prompt,
                false,
                "问题更接近闲聊、润色或通用解释，不需要访问私有知识库。",
                "general_chat",
                0.82
        );
    }

    private AgentPlan buildPlan(AgentActionType actionType,
                                String prompt,
                                boolean needRetrieval,
                                String actionReason,
                                String reasonCode,
                                double confidence) {
        AgentTool tool = toolRegistry.requireTool(actionType);
        return AgentPlan.builder()
                .thought(actionReason)
                .needRetrieval(needRetrieval)
                .actionReason(actionReason)
                .confidence(confidence)
                .plannerType("RULE_BASED")
                .action(AgentAction.builder()
                        .type(actionType)
                        .toolName(tool.getName())
                        .query(prompt)
                        .arguments(Map.of(
                                "reasonCode", reasonCode,
                                "riskLevel", tool.getRiskLevel(),
                                "toolDescription", tool.getDescription()
                        ))
                        .build())
                .build();
    }

    private boolean isCurrentTimeQuestion(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        return text.contains("几点")
                || text.contains("当前时间")
                || text.contains("现在时间")
                || text.contains("今天日期")
                || text.contains("今天几号")
                || text.contains("current time")
                || text.contains("now");
    }

    private boolean shouldRetrieve(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        return text.contains("知识库")
                || text.contains("根据文档")
                || text.contains("引用")
                || text.contains("来源")
                || text.contains("rag")
                || text.contains("pgvector")
                || text.contains("redis")
                || text.contains("mcp")
                || text.contains("memoryid")
                || text.contains("配置")
                || text.contains("错误码")
                || text.contains("类名")
                || text.contains("接口")
                || text.contains("流程")
                || text.contains("架构")
                || CODE_OR_IDENTIFIER_PATTERN.matcher(prompt).find();
    }
}
