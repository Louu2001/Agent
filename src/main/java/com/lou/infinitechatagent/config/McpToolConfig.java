package com.lou.infinitechatagent.config;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * @ClassName McpToolConfig
 * @Description
 * @Author Lou
 * @Date 2026/4/12 10:24
 */

@Configuration
@Slf4j
public class McpToolConfig {

    @Value("${bigmodel.api-key}")
    private String apiKey;

    @Value("${mcp.enabled:false}")
    private boolean mcpEnabled;

    @Value("${mcp.bigmodel-search.enabled:false}")
    private boolean bigModelSearchEnabled;

    @Value("${mcp.time.enabled:false}")
    private boolean timeMcpEnabled;

    @Bean
    public McpToolProvider mcpToolProvider() {
        if (!mcpEnabled) {
            log.info("MCP - 已禁用 MCP ToolProvider，可通过 mcp.enabled=true 开启");
            return McpToolProvider.builder()
                    .mcpClients(List.of())
                    .build();
        }

        List<McpClient> clients = new ArrayList<>();

        if (bigModelSearchEnabled && StringUtils.hasText(apiKey)) {
            McpTransport searchTransport = new HttpMcpTransport.Builder()
                    .sseUrl("https://open.bigmodel.cn/api/mcp/web_search/sse?Authorization=" + apiKey.trim())
                    .build();

            McpClient searchClient = new DefaultMcpClient.Builder()
                    .key("BigModelSearchMcpClient")
                    .transport(searchTransport)
                    .build();
            clients.add(searchClient);
        } else if (bigModelSearchEnabled) {
            log.warn("MCP - bigmodel search 已开启但 BIGMODEL_API_KEY 为空，跳过联网搜索 MCP 客户端");
        }

        if (timeMcpEnabled) {
            McpTransport timeTransport = new StdioMcpTransport.Builder()
                    .command(Arrays.asList("uvx", "mcp-server-time", "--local-timezone=Asia/Shanghai"))
                    .build();

            McpClient timeClient = new DefaultMcpClient.Builder()
                    .key("timeClient")
                    .transport(timeTransport)
                    .build();
            clients.add(timeClient);
        }

        log.info("MCP - ToolProvider 初始化完成，启用客户端数量={}", clients.size());

        return McpToolProvider.builder()
                .mcpClients(clients)
                .build();
    }
}
