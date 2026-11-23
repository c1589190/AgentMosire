// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.agentmosire.agentlib.config.FileConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Main 插件目录解析的装配测试。 */
class AppPluginsWiringTest {

  @TempDir Path tempDir;

  @Test
  void absentDefaultDirectoryIsAnEmptyInstallation() {
    Path dataDir = tempDir.resolve("data");
    App.PluginDirResolution resolution =
        App.resolvePluginsDir(new FileConfigStore(dataDir), dataDir);
    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason()).contains(dataDir.resolve("plugins").toString());
  }

  @Test
  void explicitPluginsDirWinsOverTheDefault() throws IOException {
    Path configured = tempDir.resolve("my-plugins");
    Files.createDirectories(configured);
    Files.createDirectories(tempDir.resolve("apps").resolve("plugins"));
    Path conf = tempDir.resolve("conf");
    writeConfig(conf, "{\"plugins\":{\"dir\":\"" + configured + "\"}}");

    App.PluginDirResolution resolution =
        App.resolvePluginsDir(new FileConfigStore(conf), tempDir.resolve("apps").resolve("x.jar"));

    assertThat(resolution.dir()).isEqualTo(configured.toAbsolutePath());
    assertThat(resolution.skipReason()).isNull();
  }

  @Test
  void theDefaultDirectoryFollowsTheDataRoot() throws IOException {
    Path dataDir = tempDir.resolve("data");
    Path plugins = Files.createDirectories(dataDir.resolve("plugins"));
    App.PluginDirResolution resolution =
        App.resolvePluginsDir(new FileConfigStore(dataDir), dataDir);
    assertThat(resolution.dir()).isEqualTo(plugins);
    assertThat(resolution.skipReason()).isNull();
  }

  @Test
  void eachPluginGetsOnlyItsOwnConfigurationAndDirectory() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("plugins"));
    writeConfig(root.resolve("one"), "{\"value\":1}");
    writeConfig(root.resolve("two"), "{\"value\":2}");
    var one = PluginFiles.load(root, tempDir, "one", null, java.util.Set.of());
    var two = PluginFiles.load(root, tempDir, "two", null, java.util.Set.of());
    assertThat(one.pluginDirectory()).isEqualTo(root.resolve("one"));
    assertThat(two.pluginDirectory()).isEqualTo(root.resolve("two"));
    assertThat(one.settings().path("value").asInt()).isEqualTo(1);
    assertThat(two.settings().path("value").asInt()).isEqualTo(2);
    assertThatThrownBy(() -> PluginFiles.load(root, tempDir, "../one", null, java.util.Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void explicitPluginsDirMissingFailsLoudlyAtConstruction() throws IOException {
    // 显式目录是硬要求：指向缺失目录 ≠ 缺省目录不存在（软跳过）——构造器响亮，语义由 resolvePluginsDir javadoc 定死
    Path missing = tempDir.resolve("no-such-plugins");
    Path dataDir = tempDir.resolve("data");
    writeConfig(dataDir, "{\"plugins\":{\"dir\":\"" + missing + "\"}}");

    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);
    assertThatThrownBy(() -> App.start(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("插件目录不存在");
  }

  @Test
  void enabledFalseDisablesThePluginFace() throws IOException {
    Path conf = tempDir.resolve("conf");
    writeConfig(conf, "{\"plugins\":{\"enabled\":false}}");

    App.PluginDirResolution resolution =
        App.resolvePluginsDir(new FileConfigStore(conf), tempDir.resolve("apps").resolve("x.jar"));

    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason()).contains("plugins.enabled=false");
  }

  @Test
  void nonBooleanEnabledFailsLoudly() throws IOException {
    Path conf = tempDir.resolve("conf");
    writeConfig(conf, "{\"plugins\":{\"enabled\":\"yes\"}}");

    assertThatThrownBy(
            () ->
                App.resolvePluginsDir(
                    new FileConfigStore(conf), tempDir.resolve("apps").resolve("x.jar")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("plugins.enabled");
  }

  @Test
  void nonTextualPluginsDirFailsLoudly() throws IOException {
    Path conf = tempDir.resolve("conf");
    writeConfig(conf, "{\"plugins\":{\"dir\":123}}");

    assertThatThrownBy(
            () ->
                App.resolvePluginsDir(
                    new FileConfigStore(conf), tempDir.resolve("apps").resolve("x.jar")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("plugins.dir");
  }

  // ---------- App.start 接线：真插件装载 + 生命周期事件 + 装载期校验 ----------

  private static void writeConfig(Path configRoot, String json) throws IOException {
    Files.createDirectories(configRoot);
    Files.writeString(configRoot.resolve("config.json"), json, StandardCharsets.UTF_8);
  }
}
