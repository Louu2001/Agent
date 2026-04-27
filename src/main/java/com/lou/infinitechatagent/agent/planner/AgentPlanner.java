package com.lou.infinitechatagent.agent.planner;

import com.lou.infinitechatagent.agent.dto.AgentPlan;

public interface AgentPlanner {

    AgentPlan plan(String prompt);
}
