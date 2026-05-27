package com.lou.infinitechatagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class WebSearchService {

    @Resource
    private RestClient.Builder restClientBuilder;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${web-search.enabled:false}")
    private boolean enabled;

    @Value("${web-search.endpoint:http://localhost:8081/search}")
    private String endpoint;

    @Value("${web-search.max-results:5}")
    private int maxResults;

    public WebSearchResult search(String query) {
        if (!enabled) {
            return WebSearchResult.builder()
                    .success(false)
                    .query(query)
                    .message("联网搜索未启用，请配置 WEB_SEARCH_ENABLED=true 和 WEB_SEARCH_ENDPOINT。")
                    .results(List.of())
                    .build();
        }
        if (!StringUtils.hasText(endpoint)) {
            return WebSearchResult.builder()
                    .success(false)
                    .query(query)
                    .message("联网搜索 endpoint 为空。")
                    .results(List.of())
                    .build();
        }
        try {
            String response = restClientBuilder.build()
                    .get()
                    .uri(UriComponentsBuilder.fromUriString(endpoint)
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .build()
                            .encode()
                            .toUri())
                    .retrieve()
                    .body(String.class);
            List<WebSearchResultItem> results = parseResults(response);
            return WebSearchResult.builder()
                    .success(true)
                    .query(query)
                    .message("联网搜索完成")
                    .results(results)
                    .build();
        } catch (Exception e) {
            log.warn("WebSearch - 查询失败，endpoint={}，reason={}", endpoint, e.getMessage());
            return WebSearchResult.builder()
                    .success(false)
                    .query(query)
                    .message("联网搜索失败：" + rootCauseMessage(e))
                    .results(List.of())
                    .build();
        }
    }

    private List<WebSearchResultItem> parseResults(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode resultsNode = root.path("results");
        if (!resultsNode.isArray()) {
            resultsNode = root.path("data");
        }
        if (!resultsNode.isArray()) {
            return List.of();
        }
        List<WebSearchResultItem> results = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(maxResults, 10));
        for (JsonNode node : resultsNode) {
            if (results.size() >= safeLimit) {
                break;
            }
            results.add(WebSearchResultItem.builder()
                    .title(readText(node, "title"))
                    .url(readFirstText(node, "url", "link"))
                    .content(readFirstText(node, "content", "snippet", "description"))
                    .build());
        }
        return results;
    }

    private String readFirstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = readText(node, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String readText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String rootCauseMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + (StringUtils.hasText(cause.getMessage()) ? ": " + cause.getMessage() : "");
    }
}
