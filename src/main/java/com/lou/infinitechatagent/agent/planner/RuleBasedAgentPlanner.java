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
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

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
        if (isEmailRequest(prompt)) {
            return buildPlan(
                    AgentActionType.EMAIL_SEND,
                    prompt,
                    false,
                    "用户要求发送邮件，该动作会产生外部副作用，需要走邮件工具和权限确认。",
                    "email_send_requested",
                    0.9
            );
        }
        if (isMemoryWriteRequest(prompt)) {
            return buildPlan(
                    AgentActionType.MEMORY_WRITE,
                    prompt,
                    false,
                    "用户明确要求记住长期信息，需要写入长期记忆。",
                    "memory_write_requested",
                    0.92
            );
        }
        if (isMemorySearchRequest(prompt)) {
            return buildPlan(
                    AgentActionType.MEMORY_SEARCH,
                    prompt,
                    false,
                    "用户询问之前说过的信息，需要显式查询长期记忆。",
                    "memory_search_requested",
                    0.9
            );
        }
        if (isWebSearchRequest(prompt)) {
            return buildPlan(
                    AgentActionType.WEB_SEARCH,
                    prompt,
                    false,
                    "用户要求查询最新或外部公开信息，需要调用联网搜索。",
                    "web_search_requested",
                    0.84
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

    private boolean isEmailRequest(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        return (text.contains("发邮件")
                || text.contains("发送邮件")
                || text.contains("发一封")
                || text.contains("send email")
                || text.contains("email"))
                && EMAIL_PATTERN.matcher(prompt).find();
    }

    private boolean isMemoryWriteRequest(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        return text.contains("记住")
                || text.contains("帮我记")
                || text.contains("请记下")
                || text.contains("以后记得")
                || text.contains("我的偏好是")
                || text.contains("我的技术栈是")
                || text.contains("我的项目是")
                || text.contains("项目背景是");
    }

    private boolean isMemorySearchRequest(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        return text.contains("你记得")
                || text.contains("我之前")
                || text.contains("之前说过")
                || text.contains("我的偏好是什么")
                || text.contains("我的技术栈是什么")
                || text.contains("我上次说");
    }

    private boolean isWebSearchRequest(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        return text.contains("联网")
                || text.contains("搜索一下")
                || text.contains("网上查")
                || text.contains("查一下最新")
                || text.contains("最新消息")
                || text.contains("新闻")
                || text.contains("web search")
                || text.contains("google");
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
