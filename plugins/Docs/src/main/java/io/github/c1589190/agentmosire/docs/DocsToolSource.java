package io.github.c1589190.agentmosire.docs;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.plugin.HostServices;
import io.github.c1589190.agentmosire.agentlib.plugin.ToolSource;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.workspace.WorkspaceAccess;
import java.util.List;
import org.pf4j.Extension;

/** PF4J adapter for Docs, using the Workspace dependency's live registry. */
@Extension
public final class DocsToolSource implements ToolSource {
  private List<AgentTool> tools = List.of();

  @Override
  public String id() {
    return "docs";
  }

  @Override
  public void init(HostServices services) {
    WorkspaceAccess workspace =
        services
            .dependency("workspace", WorkspaceAccess.class)
            .orElseThrow(() -> new IllegalStateException("Docs 缺少兼容的 Workspace 服务"));
    JsonNode settings = services.settings();
    JsonNode enabled = settings == null ? null : settings.get("enabled");
    if (enabled != null && !enabled.isBoolean()) {
      throw new IllegalArgumentException("Docs 的 enabled 必须是布尔值");
    }
    if (enabled == null || enabled.booleanValue()) {
      workspace.register(DocsCapability.docs());
      tools = DocsTools.all(workspace.registry());
    }
  }

  @Override
  public List<AgentTool> listTools() {
    return List.copyOf(tools);
  }

  @Override
  public AutoCloseable onChange(Runnable listener) {
    return () -> {};
  }

  @Override
  public void close() {
    tools = List.of();
  }
}
