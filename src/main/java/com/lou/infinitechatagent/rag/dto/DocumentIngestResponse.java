package com.lou.infinitechatagent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIngestResponse {

    private String path;

    private Integer chunkCount;

    private String message;
}
