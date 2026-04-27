package com.lou.infinitechatagent.rag.adaptive;

import com.lou.infinitechatagent.rag.adaptive.dto.EvidenceEvaluation;
import com.lou.infinitechatagent.rag.adaptive.dto.QueryRewriteResult;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;
import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedQueryRewriteService implements QueryRewriteService {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("[A-Z]{2,}-\\d+");
    private static final Pattern CONFIG_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+");

    @Override
    public QueryRewriteResult rewrite(String question,
                                      RetrievalPlan plan,
                                      EvidenceEvaluation evaluation,
                                      List<RetrievedChunk> previousChunks) {
        String originalQuery = plan.getQuery();
        List<String> addedTerms = collectRewriteTerms(question, evaluation, previousChunks);
        if (addedTerms.isEmpty()) {
            return QueryRewriteResult.builder()
                    .rewritten(false)
                    .originalQuery(originalQuery)
                    .rewrittenQuery(originalQuery)
                    .reason("未发现可用于改写的稳定关键词。")
                    .addedTerms(List.of())
                    .build();
        }

        String rewrittenQuery = mergeQuery(originalQuery, addedTerms);
        boolean changed = !normalize(rewrittenQuery).equals(normalize(originalQuery));
        return QueryRewriteResult.builder()
                .rewritten(changed)
                .originalQuery(originalQuery)
                .rewrittenQuery(rewrittenQuery)
                .reason(changed ? "根据证据缺失项和已召回片段补充检索关键词。" : "补充关键词已存在于原始查询中。")
                .addedTerms(addedTerms)
                .build();
    }

    private List<String> collectRewriteTerms(String question,
                                             EvidenceEvaluation evaluation,
                                             List<RetrievedChunk> previousChunks) {
        Set<String> terms = new LinkedHashSet<>();
        collectPatternTerms(ERROR_CODE_PATTERN, question, terms);
        collectPatternTerms(CONFIG_PATTERN, question, terms);
        collectMissingTerms(evaluation, terms);
        collectChunkHints(previousChunks, terms);
        collectGenericHints(question, terms);
        return terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .limit(8)
                .toList();
    }

    private void collectPatternTerms(Pattern pattern, String text, Set<String> terms) {
        if (text == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
    }

    private void collectMissingTerms(EvidenceEvaluation evaluation, Set<String> terms) {
        if (evaluation == null || evaluation.getMissingAspects() == null) {
            return;
        }
        evaluation.getMissingAspects().stream()
                .filter(item -> item.startsWith("missing_term:"))
                .map(item -> item.substring("missing_term:".length()))
                .forEach(terms::add);
    }

    private void collectChunkHints(List<RetrievedChunk> previousChunks, Set<String> terms) {
        if (previousChunks == null || previousChunks.isEmpty()) {
            return;
        }
        previousChunks.stream()
                .limit(3)
                .forEach(chunk -> {
                    collectPatternTerms(ERROR_CODE_PATTERN, chunk.getText(), terms);
                    collectPatternTerms(CONFIG_PATTERN, chunk.getText(), terms);
                    addFileNameTerms(chunk.getFileName(), terms);
                });
    }

    private void addFileNameTerms(String fileName, Set<String> terms) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        Arrays.stream(fileName.replaceAll("[^\\p{IsHan}a-zA-Z0-9_-]+", " ").split("\\s+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .limit(3)
                .forEach(terms::add);
    }

    private void collectGenericHints(String question, Set<String> terms) {
        if (question == null) {
            return;
        }
        if (question.contains("504")) {
            terms.add("超时");
            terms.add("重试");
            terms.add("处理建议");
        }
        if (question.contains("409")) {
            terms.add("状态冲突");
            terms.add("重复提交");
            terms.add("处理建议");
        }
        if (question.contains("错误") || question.contains("异常") || question.contains("报错")) {
            terms.add("原因");
            terms.add("处理建议");
        }
    }

    private String mergeQuery(String originalQuery, List<String> addedTerms) {
        List<String> parts = new ArrayList<>();
        if (originalQuery != null && !originalQuery.isBlank()) {
            parts.add(originalQuery.trim());
        }
        String normalizedOriginal = normalize(originalQuery);
        addedTerms.stream()
                .filter(term -> !normalizedOriginal.contains(normalize(term)))
                .forEach(parts::add);
        return String.join(" ", parts);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }
}
