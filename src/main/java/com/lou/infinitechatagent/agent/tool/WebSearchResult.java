package com.lou.infinitechatagent.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchResult {

    private Boolean success;

    private String query;

    private String message;

    private List<WebSearchResultItem> results;
}
