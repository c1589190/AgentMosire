package io.github.c1589190.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentlib.permission.AccessToken;
import io.github.c1589190.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentlib.permission.CommandMode;
import io.github.c1589190.agentlib.plugin.HostServices;
import io.github.c1589190.agentlib.plugin.PluginAgent;
import io.github.c1589190.agentlib.tool.ToolContext;
import io.github.c1589190.workspace.WorkspaceToolSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashWorkspaceAccessTest {
  @TempDir Path root;

  @Test
  void rejectsAnUnboundCallerAndKeepsAnAuthorizedShareReadOnly() throws Exception {
    Path shared = Files.createDirectory(root.resolve("shared"));
    Path home = Files.createDirectories(root.resolve("state/plugins/workspace"));
    ObjectMapper json = new ObjectMapper();
    var workspace = new WorkspaceToolSource();
    workspace.init(
        new HostServices(
            null,
            json.valueToTree(Map.of("roots", Map.of("shared", shared.toString()))),
            Map.of(),
            Set.of(),
            home,
            root.resolve("state"),
            null));
    var bash = new BashToolSource();
    bash.init(HostServices.none().withDependencies((id, type) -> workspace.service(type)));
    workspace.attachAgent(
        new PluginAgent(
            "main",
            null,
            "main",
            json.readTree(
                """
        {"mounts":{"shared":{"root":"shared","mode":"read-only","capabilities":["bash"]}}}
        """)));
    var tool = bash.listTools().getFirst();
    assertThat(tool.execute(context("unknown", Map.of("command", "true"))).code())
        .isEqualTo(ShellTool.DIR_NOT_ALLOWED);
    assertThat(
            tool.execute(context("main", Map.of("command", "true", "cwd", shared.toString())))
                .success())
        .isFalse();
    assertThat(
            tool.execute(
                    context("main", Map.of("command", "true", "env", Map.of("BASH_ENV", "bad"))))
                .success())
        .isFalse();
    var result =
        tool.execute(context("main", Map.of("command", "printf ok > unexpected", "cwd", "shared")));
    assertThat(Files.exists(shared.resolve("unexpected"))).isFalse();
    if (!BashSandbox.workspace().available()) {
      assertThat(result.code()).isEqualTo(ShellTool.SANDBOX_UNAVAILABLE);
    } else {
      assertThat(result.message()).contains("Read-only file system");
    }
  }

  @Test
  void workspaceSandboxCanReadAnAuthorizedShareWhenMountIsolationIsAvailable() throws Exception {
    BashSandbox sandbox = BashSandbox.workspace();
    assumeTrue(sandbox.available(), () -> sandbox.unavailableReason().orElse(""));
    Path dir = Files.createDirectory(root.resolve("shared"));
    Files.writeString(dir.resolve("note"), "fixture");
    var wrapped = sandbox.wrap(dir, List.of(), "cat note", List.of(dir), List.of());
    try {
      ProcessBuilder builder = new ProcessBuilder(wrapped.command()).redirectErrorStream(true);
      builder.environment().put("LC_ALL", "C");
      Process process = builder.start();
      String output =
          new String(
              process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(process.waitFor()).as(output).isZero();
      assertThat(output).isEqualTo("fixture");
    } finally {
      sandbox.cleanup(wrapped);
    }
  }

  private static ToolContext context(String id, Map<String, Object> arguments) {
    return new ToolContext(
        AccessToken.SYSTEM,
        AgentPermissionSet.system(),
        Map.of(),
        arguments,
        new AgentIdentity(id, CommandMode.FULL, ""));
  }
}
