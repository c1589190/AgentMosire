// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.c1589190.agentlib.llm.OpenAICompatibleLlmClient.ApiKeySource;
import io.github.c1589190.agentlib.plugin.HostServices;
import io.github.c1589190.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchToolSourceTest {

  @Test
  void absentInputsLeaveBothToolsAbsent() {
    var source = new SearchToolSource();
    source.init(HostServices.none());

    assertTrue(source.listTools().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"search", "fetch"})
  void onlyInjectedOperationIsRegisteredAndCloseClearsTheSnapshot(String operation) {
    var source = new SearchToolSource();
    source.init(HostServices.inputs(null, Map.of(operation, () -> Optional.of("valid-fixture"))));
    try {
      assertEquals("web", source.id());
      assertEquals(
          List.of("web_" + operation), source.listTools().stream().map(AgentTool::name).toList());
    } finally {
      source.close();
    }
    assertTrue(source.listTools().isEmpty());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "invalid\r\nfixture"})
  void missingOrInvalidInjectedCredentialLeavesToolsAbsent(String key) {
    var source = new SearchToolSource();
    var inputs = HostServices.inputs(null, Map.of("search", () -> Optional.ofNullable(key)));

    var failure = assertThrows(IllegalArgumentException.class, () -> source.init(inputs));
    assertEquals("tools.web.search.credentialsRef 的凭据缺失或无效", failure.getMessage());
    assertTrue(source.listTools().isEmpty());
  }

  @Test
  void failingFetchCredentialDoesNotPublishSearchOrExposeTheUnderlyingException() {
    ApiKeySource search = () -> Optional.of("search-fixture");
    ApiKeySource fetch =
        () -> {
          throw new IllegalStateException("fixture-sensitive-marker");
        };
    var inputs = HostServices.inputs(null, Map.of("search", search, "fetch", fetch));
    var source = new SearchToolSource();

    var failure = assertThrows(IllegalArgumentException.class, () -> source.init(inputs));
    assertEquals("tools.web.fetch.credentialsRef 的凭据缺失或无效", failure.getMessage());
    assertNull(failure.getCause());
    assertTrue(source.listTools().isEmpty());
  }
}
