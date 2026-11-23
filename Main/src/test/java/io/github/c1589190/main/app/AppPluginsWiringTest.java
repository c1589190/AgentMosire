// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.agentlib.config.FileConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Main 插件目录解析的装配测试。 */
class AppPluginsWiringTest {

  private static final String PLUGIN_VERSION = "0.1.0-SNAPSHOT";

  @TempDir Path tempDir;

  // ---------- resolvePluginsDir：D4 解析顺序（纯函数） ----------

  @Test
  void withoutAnyConfigTheDefaultDirectoryIsNextToTheJar() throws IOException {
    FileConfigStore store = new FileConfigStore(tempDir.resolve("conf"));
    Files.createDirectories(tempDir.resolve("apps"));
    Path jar = Files.createFile(tempDir.resolve("apps").resolve("mosire.jar"));

    App.PluginDirResolution resolution = App.resolvePluginsDir(store, jar);

    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason())
        .contains("目录不存在")
        .contains(tempDir.resolve("apps").resolve("plugins").toString());
  }

  @Test
  void classesFormStartDoesNotDeriveADefaultPluginsDirectory() throws IOException {
    FileConfigStore store = new FileConfigStore(tempDir.resolve("conf"));
    Path classesDir = Files.createDirectories(tempDir.resolve("apps").resolve("classes"));

    App.PluginDirResolution resolution = App.resolvePluginsDir(store, classesDir);

    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason()).contains("非 jar 启动");
  }

  @Test
  void missingCodeLocationDoesNotDeriveADefaultPluginsDirectory() {
    FileConfigStore store = new FileConfigStore(tempDir.resolve("conf"));

    App.PluginDirResolution resolution =
        App.resolvePluginsDir(store, tempDir.resolve("nowhere").resolve("mosire.jar"));

    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason()).contains("非 jar 启动");
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
  void defaultDirectoryIsDerivedWhenItExistsNextToTheJar() throws IOException {
    Files.createDirectories(tempDir.resolve("apps").resolve("plugins"));
    Path jar = Files.createFile(tempDir.resolve("apps").resolve("mosire.jar"));

    App.PluginDirResolution resolution =
        App.resolvePluginsDir(new FileConfigStore(tempDir.resolve("conf")), jar);

    // 生产路径的正分支：真 jar + 同目录 plugins/ ⇒ 原样返回（后续 loadAll 在其上装载）
    assertThat(resolution.dir())
        .isEqualTo(tempDir.resolve("apps").resolve("plugins").toAbsolutePath());
    assertThat(resolution.skipReason()).isNull();
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
