package com.lou.infinitechatagent.memory;

import com.lou.infinitechatagent.memory.dto.MemoryDecision;

public interface MemoryPlanner {

    MemoryDecision plan(Long userId, Long sessionId, String prompt);
}
