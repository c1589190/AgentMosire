package io.github.c1589190.agentmosire.main.app;

import io.github.c1589190.agentmosire.agentlib.plugin.PluginToolSource;
import io.github.c1589190.agentmosire.brain.subagent.AgentTemplateStore;
import io.github.c1589190.agentmosire.brain.subagent.LaunchedSubagent;
import io.github.c1589190.agentmosire.brain.subagent.SubagentInstance;
import io.github.c1589190.agentmosire.brain.subagent.SubagentLaunchException;
import io.github.c1589190.agentmosire.brain.subagent.SubagentLauncher;
import java.util.Optional;
import java.util.OptionalInt;

/** Binds plugin execution state to the child instance before it can reach its tool bridge. */
record PluginSubagentLauncher(
    SubagentLauncher delegate, PluginToolSource plugins, AgentTemplateStore templates)
    implements SubagentLauncher {

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public LaunchedSubagent launch(SubagentInstance instance) {
    Runnable detach;
    try {
      detach =
          plugins.attachAgent(
              instance.instanceId(),
              instance.parentInstanceId(),
              instance.lineagePath(),
              templates.pluginSettings(instance.templateId()).orElse(null));
    } catch (RuntimeException failure) {
      throw new SubagentLaunchException("子 Agent 插件配置被拒: " + failure.getMessage(), failure);
    }
    try {
      return new Attached(delegate.launch(instance), detach);
    } catch (RuntimeException | Error failure) {
      detach.run();
      throw failure;
    }
  }

  private record Attached(LaunchedSubagent delegate, Runnable detach) implements LaunchedSubagent {
    @Override
    public boolean isAlive() {
      return delegate.isAlive();
    }

    @Override
    public Optional<String> exitDiagnostics() {
      return delegate.exitDiagnostics();
    }

    @Override
    public OptionalInt exitCode() {
      return delegate.exitCode();
    }

    @Override
    public void close() {
      try {
        delegate.close();
      } finally {
        detach.run();
      }
    }
  }
}
