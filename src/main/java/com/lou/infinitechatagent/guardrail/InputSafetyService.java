package com.lou.infinitechatagent.guardrail;

import com.lou.infinitechatagent.guardrail.dto.InputSafetyResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class InputSafetyService {

    private static final List<String> PROMPT_INJECTION_PATTERNS = List.of(
            "忽略系统规则",
            "忽略以上规则",
            "绕过权限",
            "不要遵守",
            "ignore previous instructions",
            "ignore all previous instructions",
            "developer mode",
            "bypass restrictions"
    );

    private static final List<Pattern> VIOLENT_INTENT_PATTERNS = List.of(
            Pattern.compile(".*(怎么|如何|教我|帮我).*(杀人|伤害别人|制作武器).*"),
            Pattern.compile(".*(我要|想要).*(杀人|杀掉|伤害别人).*"),
            Pattern.compile(".*(自杀方法|如何自杀|怎么自杀).*")
    );

    public InputSafetyResult validate(String inputText) {
        if (!StringUtils.hasText(inputText)) {
            return success();
        }
        List<String> promptInjectionHits = detectPromptInjection(inputText);
        if (!promptInjectionHits.isEmpty()) {
            return blocked("PROMPT_INJECTION", "检测到疑似 Prompt Injection，请调整问题后重试。", promptInjectionHits);
        }
        List<String> violentIntentHits = detectViolentIntent(inputText);
        if (!violentIntentHits.isEmpty()) {
            return blocked("UNSAFE_INTENT", "问题包含不安全意图，无法继续处理。", violentIntentHits);
        }
        return success();
    }

    public List<String> detectPromptInjection(String inputText) {
        if (!StringUtils.hasText(inputText)) {
            return List.of();
        }
        String normalized = inputText.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String pattern : PROMPT_INJECTION_PATTERNS) {
            if (normalized.contains(pattern.toLowerCase(Locale.ROOT))) {
                hits.add("PROMPT_INJECTION:" + pattern);
            }
        }
        return hits;
    }

    private List<String> detectViolentIntent(String inputText) {
        String normalized = inputText.strip();
        List<String> hits = new ArrayList<>();
        for (Pattern pattern : VIOLENT_INTENT_PATTERNS) {
            if (pattern.matcher(normalized).matches()) {
                hits.add("UNSAFE_INTENT:" + pattern.pattern());
            }
        }
        return hits;
    }

    private InputSafetyResult success() {
        return InputSafetyResult.builder()
                .safe(true)
                .hits(List.of())
                .build();
    }

    private InputSafetyResult blocked(String riskType, String reason, List<String> hits) {
        return InputSafetyResult.builder()
                .safe(false)
                .riskType(riskType)
                .reason(reason)
                .hits(hits)
                .build();
    }
}
