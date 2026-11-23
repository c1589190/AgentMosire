// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.main.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.config.ConfigException;
import io.github.c1589190.agentmosire.agentlib.config.ConfigStore;
import io.github.c1589190.agentmosire.agentlib.llm.ConfigApiKeySource;
import io.github.c1589190.agentmosire.agentlib.llm.OpenAICompatibleLlmClient.ApiKeySource;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 将已选中的宿主凭据引用绑定为按次读取的接口。 */
public final class HostCredentials {

  private static final String FILE_PREFIX = "keys.";
  private static final String ENV_PREFIX = "env:";
  private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern ENV_VALUE = Pattern.compile("[!-~]+");
  private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private HostCredentials() {}

  /** 只收集扁平及具名模型路由中显式引用的环境变量名，供工具子进程排除。 */
  public static Set<String> modelEnvironmentNames(JsonNode llm) {
    if (llm == null) {
      return Set.of();
    }
    Set<String> names = new HashSet<>();
    environmentName(llm.path("credentialsRef").textValue()).ifPresent(names::add);
    JsonNode routes = llm.path("routes");
    if (routes.isObject()) {
      routes.forEach(
          route -> environmentName(route.path("credentialsRef").textValue()).ifPresent(names::add));
    }
    return Set.copyOf(names);
  }

  static Optional<String> environmentName(String credentialsRef) {
    String reference = credentialsRef == null ? "" : credentialsRef.strip();
    if (!reference.startsWith(ENV_PREFIX)) {
      return Optional.empty();
    }
    String name = reference.substring(ENV_PREFIX.length());
    return ENV_NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
  }

  /**
   * 从可信宿主配置绑定文件键或显式环境变量。模块收到的接口在每次请求前取值，避免在装配时缓存凭据。
   *
   * @param store 宿主的配置存储
   * @param reference 凭据引用，空白表示匿名
   */
  public static ApiKeySource source(ConfigStore store, String reference) {
    return source(store, reference, System::getenv);
  }

  static ApiKeySource source(
      ConfigStore store, String reference, Function<String, String> environment) {
    String ref = reference == null ? "" : reference.strip();
    if (!ref.startsWith(ENV_PREFIX)) {
      return fileSource(store, ref);
    }
    Objects.requireNonNull(environment, "environment");
    String name = ref.substring(ENV_PREFIX.length());
    return () -> environmentKey(name, environment);
  }

  /** 配置引导热切换只绑定引导写入的文件键。 */
  static ApiKeySource fileSource(ConfigStore store, String reference) {
    Objects.requireNonNull(store, "store");
    if (reference.isEmpty()) {
      return ApiKeySource.none();
    }
    return () -> {
      if (!reference.startsWith(FILE_PREFIX)) {
        throw unsupportedReference();
      }
      String name = reference.substring(FILE_PREFIX.length());
      JsonNode node = store.get("keys", name).orElse(null);
      if (node == null || node.isNull() || node.isContainerNode() || node.asText().isBlank()) {
        String display = SAFE_FILE_NAME.matcher(name).matches() ? name : "<不可显示的引用名>";
        throw new ConfigException(
            ConfigApiKeySource.E_KEY_MISSING, "配置里没有可取用的密钥：" + FILE_PREFIX + display);
      }
      return Optional.of(node.asText());
    };
  }

  private static Optional<String> environmentKey(
      String name, Function<String, String> environment) {
    if (!ENV_NAME.matcher(name).matches()) {
      throw unsupportedReference();
    }
    String key;
    try {
      key = environment.apply(name);
    } catch (RuntimeException failed) {
      // 来源抛出的异常可能带敏感值，此处只保留固定诊断。
      throw missingEnvironmentKey();
    }
    if (key == null || !ENV_VALUE.matcher(key).matches()) {
      throw missingEnvironmentKey();
    }
    return Optional.of(key);
  }

  private static ConfigException missingEnvironmentKey() {
    return new ConfigException(ConfigApiKeySource.E_KEY_MISSING, "模型凭据引用的环境变量缺失或格式无效");
  }

  private static ConfigException unsupportedReference() {
    return new ConfigException(
        ConfigApiKeySource.E_REF_FORM_UNSUPPORTED, "credentialsRef 须使用 keys.<name> 或 env:<变量名>");
  }
}
