package com.lou.infinitechatagent.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryTrace {

    private MemoryDecision decision;

    private MemoryContext context;

    private Boolean summaryRefreshed;

    private ReflectionResult reflection;

    private Long costMs;
}
