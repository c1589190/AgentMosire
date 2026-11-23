// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.main.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentlib.config.ConfigException;
import io.github.c1589190.agentlib.config.FileConfigStore;
import io.github.c1589190.agentlib.llm.ConfigApiKeySource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HostCredentialsTest {

  private static final String ENV_NAME = "MOSIRE_MODEL_TEST_KEY";
  private static final String FIXTURE = "fixture-sensitive-marker";

  @TempDir Path configRoot;

  @Test
  void collectsOnlyNamesExplicitlyReferencedByModelRoutes() throws IOException {
    var llm =
        new ObjectMapper()
            .readTree(
                """
                {"credentialsRef":"env:FLAT_FIXTURE",
                 "routes":{
                   "named":{"credentialsRef":"env:NAMED_FIXTURE"},
                   "duplicate":{"credentialsRef":"env:NAMED_FIXTURE"},
                   "bare":{"credentialsRef":"BARE_FIXTURE"},
                   "file":{"credentialsRef":"keys.FILE_FIXTURE"},
                   "malformed":{"credentialsRef":"env:1INVALID_FIXTURE"}
                 },
                 "otherField":"env:IGNORED_FIXTURE"}
                """);

    assertEquals(
        Set.of("FLAT_FIXTURE", "NAMED_FIXTURE"), HostCredentials.modelEnvironmentNames(llm));
  }

  @Test
  void explicitEnvironmentReferenceReadsOnlyItsNamedVariableEachTime() {
    var observed = new ArrayList<String>();
    var current = new AtomicReference<>("first-env-fixture");
    var source =
        HostCredentials.source(
            new FileConfigStore(configRoot),
            "env:" + ENV_NAME,
            name -> {
              observed.add(name);
              return current.get();
            });

    assertTrue(observed.isEmpty());
    assertEquals(Optional.of("first-env-fixture"), source.apiKey());
    current.set("second-env-fixture");
    assertEquals(Optional.of("second-env-fixture"), source.apiKey());
    assertEquals(List.of(ENV_NAME, ENV_NAME), observed);
  }

  @ParameterizedTest
  @ValueSource(strings = {ENV_NAME, "env:", "env:1BAD", "env:" + FIXTURE + "/path"})
  void rejectsBareNamesAndInvalidReferencesBeforeLookingUpEnvironment(String reference) {
    ConfigException failure =
        assertThrows(
            ConfigException.class,
            () ->
                HostCredentials.source(
                        new FileConfigStore(configRoot), reference, name -> fail("不应读取环境变量"))
                    .apiKey());
    assertEquals(ConfigApiKeySource.E_REF_FORM_UNSUPPORTED, failure.code());
    assertFalse(failure.getMessage().contains(FIXTURE));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", FIXTURE + "\r\nnext", FIXTURE + " suffix"})
  void unusableEnvironmentValueFailsWithoutFallingBackToFileKey(String value) throws IOException {
    Files.writeString(
        configRoot.resolve("config.json"),
        "{\"keys\":{\"" + ENV_NAME + "\":\"file-fallback-fixture\"}}");
    var source =
        HostCredentials.source(new FileConfigStore(configRoot), "env:" + ENV_NAME, name -> value);

    ConfigException failure = assertThrows(ConfigException.class, source::apiKey);
    assertEquals(ConfigApiKeySource.E_KEY_MISSING, failure.code());
    assertFalse(failure.getMessage().contains(FIXTURE));
    assertNull(failure.getCause());
  }

  @Test
  void failingEnvironmentLookupDoesNotExposeTheUnderlyingException() {
    var source =
        HostCredentials.source(
            new FileConfigStore(configRoot),
            "env:" + ENV_NAME,
            name -> {
              throw new IllegalStateException(FIXTURE);
            });

    ConfigException failure = assertThrows(ConfigException.class, source::apiKey);
    assertEquals(ConfigApiKeySource.E_KEY_MISSING, failure.code());
    assertFalse(failure.getMessage().contains(FIXTURE));
    assertNull(failure.getCause());
  }

  @Test
  void fileCredentialAndAnonymousRoutesNeverReadEnvironment() throws IOException {
    Path configFile = configRoot.resolve("config.json");
    Files.writeString(configFile, "{\"keys\":{\"configured\":\"first-file-fixture\"}}");
    var store = new FileConfigStore(configRoot);
    var source = HostCredentials.source(store, "keys.configured", name -> fail("不应读取环境变量"));

    assertEquals(Optional.of("first-file-fixture"), source.apiKey());
    Files.writeString(configFile, "{\"keys\":{\"configured\":\"second-file-fixture\"}}");
    assertEquals(Optional.of("second-file-fixture"), source.apiKey());
    assertTrue(HostCredentials.source(store, " ", name -> fail("不应读取环境变量")).apiKey().isEmpty());
  }
}
