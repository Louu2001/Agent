package com.lou.infinitechatagent.guardrail.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InputSafetyResult {

    private Boolean safe;

    private String reason;

    private String riskType;

    private List<String> hits;
}
