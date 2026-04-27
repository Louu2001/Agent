package com.lou.infinitechatagent.rag.adaptive;

import com.lou.infinitechatagent.rag.adaptive.dto.AdaptiveRagRequest;
import com.lou.infinitechatagent.rag.adaptive.dto.RetrievalPlan;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@Slf4j
public class AdaptiveRetrievalPlannerRouter implements RetrievalPlanner {

    @Resource
    private RuleBasedRetrievalPlanner ruleBasedRetrievalPlanner;

    @Resource
    private LlmRetrievalPlanner llmRetrievalPlanner;

    @Value("${rag.adaptive.planner.mode:RULE_BASED}")
    private String plannerMode;

    @Override
    public RetrievalPlan plan(AdaptiveRagRequest request) {
        if ("LLM".equalsIgnoreCase(plannerMode)) {
            return llmRetrievalPlanner.plan(request);
        }
        return ruleBasedRetrievalPlanner.plan(request);
    }
}
