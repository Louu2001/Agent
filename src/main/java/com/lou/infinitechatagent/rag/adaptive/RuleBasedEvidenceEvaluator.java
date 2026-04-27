package com.lou.infinitechatagent.rag.adaptive;

import com.lou.infinitechatagent.rag.adaptive.dto.EvidenceEvaluation;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;
import com.lou.infinitechatagent.rag.dto.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedEvidenceEvaluator implements EvidenceEvaluator {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("[A-Z]{2,}-\\d+");
    private static final Pattern CONFIG_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+");

    @Value("${rag.adaptive.evidence.min-top-score:0.45}")
    private double minTopScore;

    @Value("${rag.adaptive.evidence.min-coverage-score:0.5}")
    private double minCoverageScore;

    @Value("${rag.adaptive.evidence.min-citations:1}")
    private int minCitations;

    @Override
    public EvidenceEvaluation evaluate(String question, RetrievalPlan plan, List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return EvidenceEvaluation.builder()
                    .sufficient(false)
                    .topScore(0.0)
                    .coverageScore(0.0)
                    .citationCount(0)
                    .missingAspects(List.of("no_retrieved_chunks"))
                    .shouldRewrite(true)
                    .shouldAskFollowUp(false)
                    .reason("没有召回任何可用知识片段。")
                    .build();
        }

        double topScore = chunks.stream()
                .mapToDouble(this::bestScore)
                .max()
                .orElse(0.0);
        List<String> requiredTerms = extractRequiredTerms(question);
        double coverageScore = calculateCoverageScore(requiredTerms, chunks);
        List<String> missingAspects = findMissingAspects(requiredTerms, chunks);

        if (chunks.size() < minCitations) {
            missingAspects.add("citation_count_below_threshold");
        }
        if (topScore < minTopScore) {
            missingAspects.add("top_score_below_threshold");
        }
        if (!requiredTerms.isEmpty() && coverageScore < minCoverageScore) {
            missingAspects.add("required_terms_not_covered");
        }

        boolean sufficient = missingAspects.isEmpty();
        return EvidenceEvaluation.builder()
                .sufficient(sufficient)
                .topScore(topScore)
                .coverageScore(coverageScore)
                .citationCount(chunks.size())
                .missingAspects(missingAspects)
                .shouldRewrite(!sufficient)
                .shouldAskFollowUp(false)
                .reason(sufficient ? "召回证据满足当前问题回答要求。" : "召回证据不足：" + String.join(", ", missingAspects))
                .build();
    }

    private double bestScore(RetrievedChunk chunk) {
        return Math.max(
                safeScore(chunk.getRerankScore()),
                Math.max(
                        safeScore(chunk.getFusionScore()),
                        Math.max(safeScore(chunk.getVectorScore()), safeScore(chunk.getKeywordScore()))
                )
        );
    }

    private List<String> extractRequiredTerms(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        collectMatches(ERROR_CODE_PATTERN, question, terms);
        collectMatches(CONFIG_PATTERN, question, terms);
        return terms.stream()
                .map(term -> term.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private void collectMatches(Pattern pattern, String text, List<String> terms) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
    }

    private double calculateCoverageScore(List<String> requiredTerms, List<RetrievedChunk> chunks) {
        if (requiredTerms.isEmpty()) {
            return 1.0;
        }
        long covered = requiredTerms.stream()
                .filter(term -> containsTerm(chunks, term))
                .count();
        return (double) covered / requiredTerms.size();
    }

    private List<String> findMissingAspects(List<String> requiredTerms, List<RetrievedChunk> chunks) {
        if (requiredTerms.isEmpty()) {
            return new ArrayList<>();
        }
        return requiredTerms.stream()
                .filter(term -> !containsTerm(chunks, term))
                .map(term -> "missing_term:" + term)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private boolean containsTerm(List<RetrievedChunk> chunks, String term) {
        return chunks.stream()
                .map(RetrievedChunk::getText)
                .filter(text -> text != null)
                .map(text -> text.toLowerCase(Locale.ROOT))
                .anyMatch(text -> text.contains(term));
    }

    private double safeScore(Double score) {
        return score == null ? 0.0 : score;
    }
}
