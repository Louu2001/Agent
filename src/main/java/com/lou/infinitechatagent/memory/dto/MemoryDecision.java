package com.lou.infinitechatagent.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryDecision {

    private Boolean needReadMemory;

    private List<String> readTypes;

    private Boolean needWriteSummary;

    private Boolean needWriteReflection;

    private String reason;
}
