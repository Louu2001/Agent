package com.lou.infinitechatagent.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryContext {

    private Boolean summaryInjected;

    private String sessionSummary;

    private Boolean longTermMemoryInjected;

    private java.util.List<MemoryItem> longTermMemories;

    private Integer usedMemoryCount;

    private Integer estimatedMemoryTokens;
}
