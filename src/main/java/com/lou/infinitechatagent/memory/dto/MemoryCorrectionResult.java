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
public class MemoryCorrectionResult {

    private MemoryItem correctedMemory;

    private List<String> disabledMemoryIds;

    private String reason;
}
