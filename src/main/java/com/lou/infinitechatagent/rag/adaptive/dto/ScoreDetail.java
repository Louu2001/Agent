package com.lou.infinitechatagent.rag.adaptive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoreDetail {

    private String chunkId;

    private String fileName;

    private Integer chunkIndex;

    private String retrievalSource;

    private Double vectorScore;

    private Double keywordScore;

    private Double fusionScore;

    private Double rerankScore;
}
