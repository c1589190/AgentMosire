package io.github.c1589190.agentmosire.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.permission.CommandMode;
import io.github.c1589190.agentmosire.agentlib.plugin.HostServices;
import io.github.c1589190.agentmosire.agentlib.plugin.PluginAgent;
import io.github.c1589190.agentmosire.agentlib.tool.ToolCallAuthorizer;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolRegistry;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.agentmosire.workspace.MountName;
import io.github.c1589190.agentmosire.workspace.WorkspaceAccess;
import io.github.c1589190.agentmosire.workspace.WorkspaceCaller;
import io.github.c1589190.agentmosire.workspace.WorkspaceException;
import io.github.c1589190.agentmosire.workspace.WorkspaceToolSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedWorkspaceConfigurationTest {
  @TempDir Path root;

  @Test
  void configuredInstancesReadTheSameFilesButCannotWriteOrExpandTheirParentMount()
      throws Exception {
    Path shared = Files.createDirectory(root.resolve("shared"));
    Path data = Files.createDirectories(root.resolve("data/plugins/workspace"));
    Files.writeString(shared.resolve("note.md"), "shared fixture");
    ObjectMapper json = new ObjectMapper();
    var settings = json.valueToTree(Map.of("roots", Map.of("knowledge", shared.toString())));
    var workspace = new WorkspaceToolSource();
    workspace.init(
        new HostServices(null, settings, Map.of(), Set.of(), data, root.resolve("data"), null));
    var docs = new DocsToolSource();
    docs.init(HostServices.none().withDependencies((id, type) -> workspace.service(type)));
    WorkspaceAccess access = workspace.service(WorkspaceAccess.class).orElseThrow();
    ToolRegistry tools = new ToolRegistry();
    tools.registerAll(docs.listTools());

    var readOnly =
        json.readTree(
            """
        {"mounts":{"notes":{"root":"knowledge","mode":"read-only","capabilities":["docs"]}}}
        """);
    var readWrite =
        json.readTree(
            """
        {"mounts":{"notes":{"root":"knowledge","mode":"read-write","capabilities":["docs"]}}}
        """);
    workspace.attachAgent(new PluginAgent("main", null, "main", readOnly));
    workspace.attachAgent(new PluginAgent("first", "main", "main/first", readOnly));
    workspace.attachAgent(new PluginAgent("second", "main", "main/second", readOnly));
    assertThatThrownBy(
            () -> workspace.attachAgent(new PluginAgent("wide", "main", "main/wide", readWrite)))
        .isInstanceOf(WorkspaceException.class);
    assertThat(call(tools, "first", "doc_read", Map.of("mount", "notes/note.md")).message())
        .contains("shared fixture");
    assertThat(call(tools, "second", "doc_read", Map.of("mount", "notes/note.md")).message())
        .contains("shared fixture");
    assertThat(
            call(tools, "second", "doc_create", Map.of("mount", "notes/new.md", "text", "blocked"))
                .success())
        .isFalse();
    assertThat(Files.exists(shared.resolve("new.md"))).isFalse();

    access
        .registry()
        .unmount(new MountName("notes"), new WorkspaceCaller("first", AgentPermissionSet.system()));
    assertThat(call(tools, "first", "doc_read", Map.of("mount", "notes/note.md")).success())
        .isFalse();
    assertThat(call(tools, "second", "doc_read", Map.of("mount", "notes/note.md")).success())
        .isTrue();
    access
        .registry()
        .unmount(new MountName("notes"), new WorkspaceCaller("main", AgentPermissionSet.system()));
    assertThat(call(tools, "second", "doc_read", Map.of("mount", "notes/note.md")).success())
        .isFalse();
  }

  private static ToolResult call(
      ToolRegistry tools, String id, String name, Map<String, Object> args) {
    return ToolCallAuthorizer.standard()
        .execute(
            tools,
            name,
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                args,
                new AgentIdentity(id, CommandMode.FULL, "")));
  }
}
