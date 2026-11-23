// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.brain.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.config.ConfigStore;
import io.github.c1589190.agentmosire.agentlib.plugin.HostServices;
import io.github.c1589190.agentmosire.agentlib.plugin.PluginListener;
import io.github.c1589190.agentmosire.agentlib.plugin.PluginToolSource;
import io.github.c1589190.agentmosire.agentlib.plugin.ToolSource;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 标准工具来源的选择和装配。Agent 的生效权限由运行时单独应用。 */
public final class StandardToolModules implements AutoCloseable {

  /** 工作区工具来源。 */
  public static final String WORKSPACE_SOURCE_ID = "workspace";

  /** 文档工具来源。 */
  public static final String DOCS_SOURCE_ID = "docs";

  /** 搜索组件的网页工具来源。 */
  public static final String WEB_SOURCE_ID = "web";

  private static final String SEARCH_PLUGIN_ID = "search";
  private static final List<String> SOURCE_ORDER =
      List.of(WORKSPACE_SOURCE_ID, DOCS_SOURCE_ID, WEB_SOURCE_ID);

  private final ToolRegistry registry;
  private final List<Runnable> cleanup = new ArrayList<>();

  private StandardToolModules(ToolRegistry registry) {
    this.registry = registry;
  }

  static StandardToolModules open(
      ToolRegistry registry,
      Set<String> enabled,
      List<? extends ToolSource> available,
      HostServices services) {
    for (String id : enabled) {
      requireStandardId(id);
    }
    if (enabled.contains(DOCS_SOURCE_ID) && !enabled.contains(WORKSPACE_SOURCE_ID)) {
      throw new IllegalArgumentException("启用 docs 标准工具须同时启用 workspace");
    }
    Map<String, ToolSource> sources = new HashMap<>();
    for (ToolSource source : available) {
      String id = source.id();
      requireStandardId(id);
      if (sources.putIfAbsent(id, source) != null) {
        throw new IllegalArgumentException("标准工具来源重复: " + id);
      }
    }
    synchronized (registry) {
      for (String id : SOURCE_ORDER) {
        if (enabled.contains(id)) {
          if (!sources.containsKey(id)) {
            throw new IllegalStateException("未装载已启用的标准工具来源: " + id);
          }
          if (!registry.listBySource(id).isEmpty()) {
            throw new IllegalStateException("标准工具来源已登记: " + id);
          }
        }
      }
      StandardToolModules modules = new StandardToolModules(registry);
      try {
        for (String id : SOURCE_ORDER) {
          if (enabled.contains(id)) {
            modules.install(id, sources.get(id), services);
          }
        }
        return modules;
      } catch (RuntimeException | Error failure) {
        try {
          modules.close();
        } catch (RuntimeException | Error cleanupFailure) {
          if (failure != cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
        throw failure;
      }
    }
  }

  private void install(String id, ToolSource source, HostServices services) {
    cleanup.add(source::close);
    source.init(services);
    List<AgentTool> tools = List.copyOf(source.listTools());
    if (tools.isEmpty()) {
      throw new IllegalStateException("已启用的标准工具来源没有提供工具: " + id);
    }
    cleanup.add(registry.registerOwnedSource(id, tools));
  }

  private static void requireStandardId(String id) {
    if (id == null || !SOURCE_ORDER.contains(id)) {
      throw new IllegalArgumentException("未知标准工具来源");
    }
  }

  /** 校验宿主是否装载了显式启用的搜索插件。宿主继续负责插件的加载与关闭，这里只校验已发布的来源。 */
  public static void requireWebPlugin(
      ConfigStore store, PluginToolSource plugins, ToolRegistry registry) {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(registry, "registry");
    JsonNode web = store.get("tools", WEB_SOURCE_ID).orElse(null);
    if (web == null) {
      return;
    }
    if (!web.isObject()) {
      throw new IllegalArgumentException("tools.web 须为对象");
    }
    JsonNode enabled = web.get("enabled");
    if (enabled == null) {
      return;
    }
    if (!enabled.isBoolean()) {
      throw new IllegalArgumentException("tools.web.enabled 须为布尔值");
    }
    requireWebPlugin(enabled.booleanValue(), plugins, registry);
  }

  /** 根据宿主传入的开关，校验搜索插件已经发布可用的网页工具。 */
  public static void requireWebPlugin(
      boolean enabled, PluginToolSource plugins, ToolRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    if (!enabled) {
      return;
    }
    boolean loaded =
        plugins != null
            && plugins.list().stream()
                .anyMatch(
                    status ->
                        SEARCH_PLUGIN_ID.equals(status.pluginId())
                            && WEB_SOURCE_ID.equals(status.sourceId())
                            && status.state() == PluginListener.State.STARTED);
    if (!loaded || registry.listBySource(WEB_SOURCE_ID).isEmpty()) {
      throw new IllegalStateException("tools.web.enabled 已启用，未装载可用的 search-mosire 插件");
    }
  }

  /** 撤销本次登记并关闭本次启动的来源。单个来源失败时仍清理其他来源。 */
  @Override
  public void close() {
    synchronized (registry) {
      Throwable failure = null;
      while (!cleanup.isEmpty()) {
        try {
          cleanup.removeLast().run();
        } catch (RuntimeException | Error e) {
          if (failure == null) {
            failure = e;
          } else if (failure != e) {
            failure.addSuppressed(e);
          }
        }
      }
      if (failure instanceof RuntimeException exception) {
        throw new IllegalStateException("关闭标准工具来源失败", exception);
      }
      if (failure instanceof Error error) {
        throw error;
      }
    }
  }
}
