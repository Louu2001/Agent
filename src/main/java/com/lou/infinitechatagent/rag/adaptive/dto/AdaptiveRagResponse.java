package com.lou.infinitechatagent.rag.adaptive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lou.infinitechatagent.rag.dto.Citation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdaptiveRagResponse {

    private String answer;

    private List<Citation> citations;

    private Boolean hit;

    private String strategy;

    private Integer rounds;

    private AdaptiveRagDebug debug;
}
