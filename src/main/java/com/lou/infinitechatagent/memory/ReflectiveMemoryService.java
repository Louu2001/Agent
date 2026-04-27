package com.lou.infinitechatagent.memory;

import com.lou.infinitechatagent.memory.dto.MemoryItem;
import com.lou.infinitechatagent.memory.dto.MemoryType;
import com.lou.infinitechatagent.memory.dto.MemoryWriteRequest;
import com.lou.infinitechatagent.memory.dto.ReflectionRequest;
import com.lou.infinitechatagent.memory.dto.ReflectionResult;
import com.lou.infinitechatagent.memory.dto.ReflectionTrigger;
import com.lou.infinitechatagent.rag.adaptive.dto.EvidenceEvaluation;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class ReflectiveMemoryService {

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Value("${memory.reflection.enabled:true}")
    private boolean reflectionEnabled;

    @Value("${memory.reflection.min-confidence:0.55}")
    private double minConfidence;

    public ReflectionResult reflect(ReflectionRequest request) {
        if (!reflectionEnabled) {
            return skipped("反思记忆未启用");
        }
        if (request == null || request.getUserId() == null) {
            return skipped("缺少 userId");
        }
        ReflectionTrigger trigger = request.getTrigger() == null ? ReflectionTrigger.LOW_CONFIDENCE : request.getTrigger();
        double confidence = request.getConfidence() == null ? defaultConfidence(trigger) : request.getConfidence();
        if (confidence < minConfidence) {
            return skipped("反思置信度低于阈值");
        }
        String content = buildContent(request, trigger);
        if (!StringUtils.hasText(content)) {
            return skipped("没有可沉淀的反思内容");
        }
        MemoryWriteRequest writeRequest = new MemoryWriteRequest();
        writeRequest.setUserId(request.getUserId());
        writeRequest.setSessionId(request.getSessionId());
        writeRequest.setMemoryType(MemoryType.REFLECTION);
        writeRequest.setContent(content);
        writeRequest.setSummary(buildSummary(request, trigger));
        writeRequest.setConfidence(confidence);
        writeRequest.setSource("reflection:" + trigger.name().toLowerCase());
        MemoryItem memory = longTermMemoryService.write(writeRequest);
        return ReflectionResult.builder()
                .written(true)
                .reason("已写入反思记忆")
                .memory(memory)
                .build();
    }

    public ReflectionResult reflectEvidenceFailure(Long userId,
                                                   Long sessionId,
                                                   String prompt,
                                                   EvidenceEvaluation evaluation,
                                                   int rounds) {
        ReflectionRequest request = new ReflectionRequest();
        request.setUserId(userId);
        request.setSessionId(sessionId);
        request.setTrigger(rounds > 1 ? ReflectionTrigger.RETRIEVAL_FAILED : ReflectionTrigger.EVIDENCE_INSUFFICIENT);
        request.setPrompt(prompt);
        request.setAnswer("当前知识库未提供足够信息，无法回答该问题。");
        request.setReason(evaluation == null ? "未召回足够证据" : evaluation.getReason());
        request.setMissingAspects(evaluation == null ? List.of() : evaluation.getMissingAspects());
        request.setConfidence(calculateConfidence(evaluation, rounds));
        return reflect(request);
    }

    private String buildContent(ReflectionRequest request, ReflectionTrigger trigger) {
        return switch (trigger) {
            case EVIDENCE_INSUFFICIENT, RETRIEVAL_FAILED -> """
                    当用户问题涉及“%s”但证据评估不足时，不应强行回答；应优先补充检索关键词、扩大 hybrid 检索范围，必要时要求用户补充错误码、配置项、类名或业务对象。失败原因：%s。缺失方面：%s。
                    """.formatted(safeSnippet(request.getPrompt()), safeText(request.getReason()), missingText(request.getMissingAspects())).strip();
            case USER_CORRECTION -> """
                    用户对上一次回答进行了纠正。后续遇到类似问题时，应优先采纳用户纠正内容，并避免重复旧结论。纠正内容：%s。
                    """.formatted(safeSnippet(request.getPrompt())).strip();
            case TOOL_FAILED -> """
                    工具调用失败时，应明确说明失败原因，避免伪造工具结果，并给出可重试或替代路径。失败原因：%s。
                    """.formatted(safeText(request.getReason())).strip();
            case LOW_CONFIDENCE -> """
                    当回答置信度较低时，应主动降低断言强度，说明不确定性，并优先请求更多上下文或触发检索。问题：%s。
                    """.formatted(safeSnippet(request.getPrompt())).strip();
        };
    }

    private String buildSummary(ReflectionRequest request, ReflectionTrigger trigger) {
        return switch (trigger) {
            case EVIDENCE_INSUFFICIENT -> "反思：证据不足时不要强答，应补充检索或追问关键信息。";
            case RETRIEVAL_FAILED -> "反思：多轮检索仍失败时，应调整检索词、扩大 hybrid 检索并提示缺失信息。";
            case USER_CORRECTION -> "反思：用户纠正后应优先采纳纠正内容，避免重复旧结论。";
            case TOOL_FAILED -> "反思：工具失败时应说明原因，不伪造工具结果。";
            case LOW_CONFIDENCE -> "反思：低置信度回答应降低断言并请求补充上下文。";
        };
    }

    private double calculateConfidence(EvidenceEvaluation evaluation, int rounds) {
        if (evaluation == null) {
            return 0.7;
        }
        double topScore = evaluation.getTopScore() == null ? 0 : evaluation.getTopScore();
        double coverageScore = evaluation.getCoverageScore() == null ? 0 : evaluation.getCoverageScore();
        double penalty = Math.min(0.3, (topScore + coverageScore) / 4);
        return Math.max(minConfidence, 0.9 - penalty + Math.min(0.08, rounds * 0.04));
    }

    private double defaultConfidence(ReflectionTrigger trigger) {
        return switch (trigger) {
            case USER_CORRECTION -> 0.9;
            case RETRIEVAL_FAILED -> 0.86;
            case EVIDENCE_INSUFFICIENT -> 0.82;
            case TOOL_FAILED -> 0.8;
            case LOW_CONFIDENCE -> 0.7;
        };
    }

    private ReflectionResult skipped(String reason) {
        return ReflectionResult.builder()
                .written(false)
                .reason(reason)
                .build();
    }

    private String missingText(List<String> missingAspects) {
        if (missingAspects == null || missingAspects.isEmpty()) {
            return "未明确";
        }
        return String.join("、", missingAspects);
    }

    private String safeSnippet(String text) {
        String safe = safeText(text);
        return safe.length() <= 80 ? safe : safe.substring(0, 80) + "...";
    }

    private String safeText(String text) {
        return StringUtils.hasText(text) ? text.strip() : "未提供";
    }
}
