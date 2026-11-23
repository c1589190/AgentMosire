package io.github.c1589190.workspace;

import io.github.c1589190.agentlib.permission.ResourceScopeMap;
import io.github.c1589190.agentlib.plugin.HostServices;
import io.github.c1589190.agentlib.plugin.PluginAgent;
import io.github.c1589190.agentlib.plugin.ToolSource;
import io.github.c1589190.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Optional;
import org.pf4j.Extension;

/** PF4J provider for Workspace tools and the shared workspace service. */
@Extension
public final class WorkspaceToolSource implements ToolSource {
  private WorkspaceAccess access;

  @Override
  public String id() {
    return "workspace";
  }

  @Override
  public void init(HostServices services) {
    access = new WorkspaceAccess(services);
  }

  @Override
  public <T> Optional<T> service(Class<T> type) {
    return type == WorkspaceAccess.class ? Optional.of(type.cast(access)) : Optional.empty();
  }

  @Override
  public Runnable attachAgent(PluginAgent agent) {
    return access.attach(agent);
  }

  @Override
  public ResourceScopeMap agentResourceLimits(String instanceId) {
    return access.registry().resourceLimits(instanceId);
  }

  @Override
  public List<AgentTool> listTools() {
    return WorkspaceTools.all(access.registry());
  }

  @Override
  public AutoCloseable onChange(Runnable listener) {
    return () -> {};
  }

  @Override
  public void close() {}
}
