package com.lou.infinitechatagent.memory;

import com.lou.infinitechatagent.memory.dto.MemoryDecision;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class RuleBasedMemoryPlanner implements MemoryPlanner {

    @Override
    public MemoryDecision plan(Long userId, Long sessionId, String prompt) {
        boolean hasUser = userId != null;
        boolean hasSession = sessionId != null;
        boolean promptLooksContextual = isContextual(prompt);
        List<String> readTypes = new ArrayList<>();
        if (hasSession) {
            readTypes.add("SESSION_SUMMARY");
        }
        if (hasUser) {
            readTypes.add("LONG_TERM_MEMORY");
            readTypes.add("REFLECTION");
        }
        return MemoryDecision.builder()
                .needReadMemory(hasUser || hasSession || promptLooksContextual)
                .readTypes(readTypes)
                .needWriteSummary(hasUser && hasSession)
                .needWriteReflection(false)
                .reason(reason(hasUser, hasSession, promptLooksContextual))
                .build();
    }

    private boolean isContextual(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return false;
        }
        return prompt.contains("继续")
                || prompt.contains("上面")
                || prompt.contains("刚刚")
                || prompt.contains("之前")
                || prompt.contains("我这个")
                || prompt.contains("我的项目");
    }

    private String reason(boolean hasUser, boolean hasSession, boolean promptLooksContextual) {
        if (hasUser && hasSession) {
            return "存在 userId 和 sessionId，读取会话摘要、长期记忆和反思记忆，并在回答后按需刷新摘要。";
        }
        if (promptLooksContextual) {
            return "问题包含上下文延续表达，尝试读取可用记忆。";
        }
        return "缺少稳定用户或会话标识，仅使用可用的默认记忆策略。";
    }
}
