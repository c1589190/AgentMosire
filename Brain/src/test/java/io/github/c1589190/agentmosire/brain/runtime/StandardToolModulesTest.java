// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.agentmosire.agentlib.agent.AgentConfig;
import io.github.c1589190.agentmosire.agentlib.llm.LlmResponse;
import io.github.c1589190.agentmosire.agentlib.permission.ToolSpec;
import io.github.c1589190.agentmosire.agentlib.plugin.HostServices;
import io.github.c1589190.agentmosire.agentlib.plugin.ToolSource;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolRegistry;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StandardToolModulesTest {

  private static final AgentSpec SPEC = new AgentSpec(AgentConfig.builder("embedded").build());
  private static final String WORKSPACE = StandardToolModules.WORKSPACE_SOURCE_ID;
  private static final String DOCS = StandardToolModules.DOCS_SOURCE_ID;
  private static final String WEB = StandardToolModules.WEB_SOURCE_ID;

  @Test
  void initializesOnlySelectedSourcesAndClosesOnlyOwnedRegistrations() {
    List<String> lifecycle = new ArrayList<>();
    AgentTool reused = tool("reused");
    ToolSource workspace =
        new RecordingSource(
            WORKSPACE, List.of(tool("original"), tool("owned"), reused), lifecycle, false);
    ToolSource web = source(WEB, lifecycle);
    ToolRegistry registry = new ToolRegistry();
    AgentTool borrowed = registry.register(tool("borrowed"));
    AgentTool replacement = tool("original");

    try (AgentRuntime runtime =
        builder()
            .toolRegistry(registry)
            .standardTools(Set.of(WORKSPACE), List.of(web, workspace), HostServices.none())
            .build()) {
      assertThat(runtime.registry()).isSameAs(registry);
      assertThat(registry.listBySource(WORKSPACE))
          .extracting(AgentTool::name)
          .containsExactly("original", "owned", "reused");
      assertThat(registry.listBySource(WEB)).isEmpty();
      assertThat(lifecycle).containsExactly("init:workspace");

      registry.unregister("original");
      registry.register(WORKSPACE, replacement);
      registry.unregister("reused");
      registry.register(WORKSPACE, reused);
    }

    assertThat(lifecycle).containsExactly("init:workspace", "close:workspace");
    assertThat(registry.listBySource(WORKSPACE)).containsExactly(replacement, reused);
    assertThat(registry.find("borrowed")).containsSame(borrowed);
  }

  @Test
  void rejectsInvalidSourceSelectionsBeforeInitializingAnySource() {
    List<String> lifecycle = new ArrayList<>();
    ToolSource workspace = source(WORKSPACE, lifecycle);
    ToolSource docs = source(DOCS, lifecycle);

    assertThatThrownBy(
            () ->
                builder()
                    .standardTools(Set.of(WEB), List.of(workspace), HostServices.none())
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未装载已启用的标准工具来源: web");
    assertThatThrownBy(
            () -> builder().standardTools(Set.of(DOCS), List.of(docs), HostServices.none()).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同时启用 workspace");
    assertThatThrownBy(
            () ->
                builder()
                    .standardTools(
                        Set.of(WORKSPACE), List.of(workspace, workspace), HostServices.none())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("标准工具来源重复");
    assertThatThrownBy(
            () ->
                builder()
                    .standardTools(Set.of("unapproved"), List.of(workspace), HostServices.none())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知标准工具来源");

    assertThat(lifecycle).isEmpty();
  }

  @Test
  void closesPartiallyInitializedSourcesAndRollsBackEarlierRegistrations() {
    List<String> lifecycle = new ArrayList<>();
    ToolSource workspace = source(WORKSPACE, lifecycle);
    ToolSource web = new RecordingSource(WEB, List.of(tool(WEB)), lifecycle, true);
    ToolRegistry registry = new ToolRegistry();
    AgentTool borrowed = registry.register(tool("borrowed"));

    assertThatThrownBy(
            () ->
                builder()
                    .toolRegistry(registry)
                    .standardTools(
                        Set.of(WORKSPACE, WEB), List.of(web, workspace), HostServices.none())
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("测试初始化失败");

    assertThat(lifecycle)
        .containsExactly("init:workspace", "init:web", "close:web", "close:workspace");
    assertThat(registry.list()).containsExactly(borrowed);
  }

  private static AgentRuntime.Builder builder() {
    return AgentRuntime.builder(SPEC, request -> LlmResponse.text("未调用模型"));
  }

  private static ToolSource source(String id, List<String> lifecycle) {
    return new RecordingSource(id, List.of(tool(id)), lifecycle, false);
  }

  private static AgentTool tool(String name) {
    return AgentTool.of(
        name, "测试工具", Map.of("type", "object"), ToolSpec.DEFAULT, context -> ToolResult.ok(name));
  }

  private record RecordingSource(
      String id, List<AgentTool> listTools, List<String> lifecycle, boolean failOnInit)
      implements ToolSource {
    @Override
    public void init(HostServices services) {
      lifecycle.add("init:" + id);
      if (failOnInit) {
        throw new IllegalStateException("测试初始化失败");
      }
    }

    @Override
    public AutoCloseable onChange(Runnable listener) {
      return () -> {};
    }

    @Override
    public void close() {
      lifecycle.add("close:" + id);
    }
  }
}
