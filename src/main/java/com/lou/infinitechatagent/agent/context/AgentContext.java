package com.lou.infinitechatagent.agent.context;

import com.lou.infinitechatagent.memory.dto.MemoryContext;
import com.lou.infinitechatagent.memory.dto.MemoryTrace;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    private String prompt;

    private MemoryTrace memoryTrace;

    private MemoryContext memoryContext;

    private String memoryText;

    private String historyText;

    private Boolean historyCompacted;

    private Boolean contextTruncated;

    private Integer estimatedInputTokens;
}
