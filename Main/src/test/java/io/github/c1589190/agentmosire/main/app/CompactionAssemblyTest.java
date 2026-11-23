// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.agentmosire.agentlib.config.ConfigException;
import io.github.c1589190.agentmosire.agentlib.llm.LlmClient;
import io.github.c1589190.agentmosire.agentlib.llm.LlmResponse;
import io.github.c1589190.agentmosire.agentlib.llm.LlmRouteLoader;
import io.github.c1589190.agentmosire.agentlib.llm.ModelCapabilities;
import io.github.c1589190.agentmosire.brain.compact.ContextCompactor;
import org.junit.jupiter.api.Test;

/** Main/Subagent 共用的严格容量装配：缺任一容量键必须在启动装配期点名失败。 */
class CompactionAssemblyTest {

  private static final LlmClient UNUSED = request -> LlmResponse.text("不应调用");

  @Test
  void missingMaxContextFailsWithTheExactCapabilityKeyInTheMessage() {
    ModelCapabilities capabilities = capabilities(0, 8192);

    assertThatThrownBy(() -> CompactionAssembly.defaultCompactor(UNUSED, capabilities, 0))
        .isInstanceOf(ConfigException.class)
        .satisfies(
            failure ->
                assertThat(((ConfigException) failure).code())
                    .isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING))
        .hasMessageContaining("capabilities.maxContext");
  }

  @Test
  void missingMaxOutputFailsWithTheExactCapabilityKeyInTheMessage() {
    ModelCapabilities capabilities = capabilities(131072, 0);

    assertThatThrownBy(() -> CompactionAssembly.defaultCompactor(UNUSED, capabilities, 0))
        .isInstanceOf(ConfigException.class)
        .satisfies(
            failure ->
                assertThat(((ConfigException) failure).code())
                    .isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING))
        .hasMessageContaining("capabilities.maxOutput");
  }

  @Test
  void validCapabilitiesProduceACompactorWithoutCallingTheLlm() {
    ContextCompactor compactor =
        CompactionAssembly.defaultCompactor(UNUSED, capabilities(131072, 8192), 0);
    assertThat(compactor).isNotNull();
  }

  private static ModelCapabilities capabilities(int maxContext, int maxOutput) {
    return new ModelCapabilities(true, false, false, false, false, maxContext, maxOutput);
  }
}
