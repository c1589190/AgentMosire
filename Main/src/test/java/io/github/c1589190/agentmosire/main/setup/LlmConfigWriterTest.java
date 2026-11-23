// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.main.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.config.FileConfigStore;
import io.github.c1589190.agentmosire.agentlib.llm.ConfigApiKeySource;
import io.github.c1589190.agentmosire.agentlib.llm.LlmRouteLoader;
import io.github.c1589190.agentmosire.agentlib.llm.ModelRoute;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link LlmConfigWriter}：写入后经<b>读侧原路</b>（{@link LlmRouteLoader} + {@link ConfigApiKeySource}）
 * 读得回——写读同源是判据，不是写完看 JSON 长相；兄弟路由保留、匿名形态、非法入参各一钉。
 */
class LlmConfigWriterTest {

  @TempDir Path tempDir;

  @Test
  void writtenRouteAndKeyRoundTripThroughTheLoaders() {
    FileConfigStore store = new FileConfigStore(tempDir);

    LlmConfigWriter.write(
        store, "default", "https://example.com/v4", "glm-5.3-flash", "sk-abc", 131072, 8192);

    ModelRoute route = LlmRouteLoader.load(store);
    assertThat(route.name()).isEqualTo("default");
    assertThat(route.baseUrl()).isEqualTo("https://example.com/v4");
    assertThat(route.model()).isEqualTo("glm-5.3-flash");
    assertThat(route.credentialsRef()).isEqualTo("keys.default");
    var capabilities = LlmRouteLoader.capabilities(store, "default");
    assertThat(capabilities.maxContext()).isEqualTo(131072);
    assertThat(capabilities.maxOutput()).isEqualTo(8192);
    assertThat(new ConfigApiKeySource(store, route.credentialsRef(), AccessToken.SYSTEM).apiKey())
        .isEqualTo(Optional.of("sk-abc"));
  }

  @Test
  void officialDeepSeekSetupPersistsReasoningReplay() {
    FileConfigStore store = new FileConfigStore(tempDir);
    LlmConfigWriter.write(
        store,
        "official",
        "https://api.deepseek.com/v1",
        "deepseek-flash",
        "test-key",
        131072,
        8192);
    LlmConfigWriter.write(
        store, "relay", "https://relay.example/v1", "deepseek-flash", "test-key", 131072, 8192);

    assertThat(LlmRouteLoader.load(store, "official").transport().echoReasoningContent()).isTrue();
    assertThat(LlmRouteLoader.load(store, "relay").transport().echoReasoningContent()).isFalse();
  }

  @Test
  void siblingRoutesSurviveANewWrite() {
    FileConfigStore store = new FileConfigStore(tempDir);
    LlmConfigWriter.write(
        store, "glm", "https://glm.example/v4", "glm-5.3-flash", "sk-glm", 131072, 8192);

    LlmConfigWriter.write(
        store, "default", "https://ds.example", "deepseek-chat", "sk-ds", 65536, 4096);

    assertThat(LlmRouteLoader.load(store, "glm").model()).isEqualTo("glm-5.3-flash");
    assertThat(LlmRouteLoader.load(store, "default").model()).isEqualTo("deepseek-chat");
    assertThat(new ConfigApiKeySource(store, "keys.glm", AccessToken.SYSTEM).apiKey())
        .isEqualTo(Optional.of("sk-glm"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void updatingConnectionPreservesTheStartupOptionsOfNamedAndFlatDefaultRoutes(boolean named)
      throws IOException {
    FileConfigStore store = new FileConfigStore(tempDir);
    var config = new ObjectMapper().createObjectNode();
    var llm = config.putObject("llm");
    var existing = named ? llm.putObject("routes").putObject("default") : llm;
    String prompt = "保存的启动提示词\n保留换行";
    existing
        .put("baseUrl", "https://example.com/v1")
        .put("model", "old-model")
        .put("reasoningEffort", "low")
        .put("systemPrompt", prompt);
    Files.writeString(tempDir.resolve("config.json"), config.toString());

    LlmConfigWriter.write(store, "default", "https://example.com/v2", "new-model", "", 32768, 2048);

    assertThat(LlmRouteLoader.load(store).model()).isEqualTo("new-model");
    var updated = store.get("llm.routes", "default").orElseThrow();
    assertThat(updated.path("reasoningEffort").asText()).isEqualTo("low");
    assertThat(updated.path("systemPrompt").asText()).isEqualTo(prompt);
  }

  @Test
  void blankApiKeyMeansAnonymousAndWritesNoKey() {
    FileConfigStore store = new FileConfigStore(tempDir);

    LlmConfigWriter.write(
        store, "default", "http://localhost:11434/v1", "llama3", "  ", 32768, 2048);

    ModelRoute route = LlmRouteLoader.load(store);
    assertThat(route.credentialsRef()).isEmpty();
    assertThat(new ConfigApiKeySource(store, "", AccessToken.SYSTEM).apiKey()).isEmpty();
    assertThat(store.get("keys", "default")).isEmpty();
  }

  @Test
  void rejectsBadRouteNameAndBlankRequiredFields() {
    FileConfigStore store = new FileConfigStore(tempDir);

    assertThatThrownBy(() -> LlmConfigWriter.write(store, "bad name", "https://x", "m", "", 1, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("路由名非法");
    assertThatThrownBy(() -> LlmConfigWriter.write(store, "default", " ", "m", "", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LlmConfigWriter.write(store, "default", "https://x", " ", "", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LlmConfigWriter.write(store, "default", "https://x", "m", "", 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capabilities.maxContext");
    assertThatThrownBy(() -> LlmConfigWriter.write(store, "default", "https://x", "m", "", 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capabilities.maxOutput");
  }
}
