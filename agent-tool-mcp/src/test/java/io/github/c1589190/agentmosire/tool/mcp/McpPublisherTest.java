package io.github.c1589190.agentmosire.tool.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolImage;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolRegistry;
import io.github.c1589190.agentmosire.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

class McpPublisherTest {
    @Test
    void imagesUseNativeMcpContent() {
        var result = McpPublisher.result(ToolResult.okImage("Reference", new ToolImage("image/png", new byte[] {1, 2, 3})));
        var image = assertInstanceOf(McpSchema.ImageContent.class, result.content().getLast());
        assertEquals("AQID", image.data());
        assertEquals("image/png", image.mimeType());
        assertFalse(result.structuredContent().toString().contains("AQID"));
    }

    @Test
    void publicationRejectsInternalToolsAndKeepsApprovalResultCode() {
        var id = new ToolId("scope", "internal");
        var tool = new ToolDefinition(id, "Internal tool", "{\"type\":\"object\"}", false,
                args -> List.of(), (caller, args) -> ToolDefinition.Verdict.ASK,
                (args, access) -> fail("Internal tool cannot be called through MCP"));
        var invoker = new ToolInvoker(new ToolRegistry(List.of(new ToolModule("scope", List.of(tool)))),
                new RolePolicy(caller -> Set.of(), List.of()));
        var options = new McpPublisher.Options("mcp", "1", "/mcp", "test.read", List.of("localhost:*"), List.of());
        assertThrows(IllegalArgumentException.class, () -> new McpPublisher(options, invoker, Set.of(id)));
        var result = McpPublisher.result(ToolResult.of(ToolResult.Code.APPROVAL_REQUIRED, "Approval required"));
        assertTrue(result.isError());
        assertEquals("APPROVAL_REQUIRED", ((Map<?, ?>) result.structuredContent()).get("code"));
    }
}
