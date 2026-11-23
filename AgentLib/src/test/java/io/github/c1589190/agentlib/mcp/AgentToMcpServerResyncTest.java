// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.c1589190.agentlib.permission.AccessToken;
import io.github.c1589190.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentlib.tool.AgentTool;
import io.github.c1589190.agentlib.tool.ToolContext;
import io.github.c1589190.agentlib.tool.ToolRegistry;
import io.github.c1589190.agentlib.tool.ToolResult;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** A4：{@code resync()} 按当前 include 谓词重算暴露面，幂等且不需要 Registry 增删。 */
class AgentToMcpServerResyncTest {

  @Test
  void resyncReevaluatesIncludeWithoutRegistryChanges() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("ping"));
    registry.register(tool("secret"));
    AtomicBoolean exposeSecret = new AtomicBoolean(false);

    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(
            registry,
            "resync-test",
            "0.1.0",
            ToolContext.of(
                AccessToken.READONLY, AgentPermissionSet.unrestricted(AccessToken.READONLY)),
            new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverReads, serverWrites),
            name -> "ping".equals(name) || exposeSecret.get())) {
      assertThat(server.toolNames()).containsExactly("ping");

      exposeSecret.set(true);
      server.resync();
      assertThat(server.toolNames()).containsExactlyInAnyOrder("ping", "secret");

      // 幂等：重复调用不改变暴露面
      server.resync();
      assertThat(server.toolNames()).containsExactlyInAnyOrder("ping", "secret");

      exposeSecret.set(false);
      server.resync();
      assertThat(server.toolNames()).containsExactly("ping");
    }
  }

  private static AgentTool tool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name);
      }
    };
  }
}
