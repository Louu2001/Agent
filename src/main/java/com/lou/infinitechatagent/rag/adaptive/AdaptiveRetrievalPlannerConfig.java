package com.lou.infinitechatagent.rag.adaptive;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdaptiveRetrievalPlannerConfig {

    @Bean
    public RuleBasedRetrievalPlanner ruleBasedRetrievalPlanner() {
        return new RuleBasedRetrievalPlanner();
    }
}
