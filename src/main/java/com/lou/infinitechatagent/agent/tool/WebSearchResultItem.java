package com.lou.infinitechatagent.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchResultItem {

    private String title;

    private String url;

    private String content;
}
