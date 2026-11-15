package com.agentpaygate.gateway.config;

import com.agentpaygate.gateway.web.McpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the annotated tool methods with the MCP server. The transport is Streamable HTTP at
 * {@code /mcp} (Spring AI's {@code STREAMABLE} protocol), which is why the Go edge must not buffer
 * responses.
 */
@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider paymentToolCallbacks(McpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
