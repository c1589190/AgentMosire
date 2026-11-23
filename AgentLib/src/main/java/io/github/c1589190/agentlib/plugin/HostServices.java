// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentlib.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.c1589190.agentlib.config.ConfigStore;
import io.github.c1589190.agentlib.llm.OpenAICompatibleLlmClient.ApiKeySource;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 在 {@link ToolSource#init} 时注入宿主已为该插件选定的配置字段与凭据来源。 */
public record HostServices(
    ConfigStore config,
    JsonNode settings,
    Map<String, ApiKeySource> credentials,
    Set<String> excludedEnvironment) {

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "兼容旧入口，配置存储按约定由宿主共享。")
  public HostServices {
    settings = settings == null ? null : settings.deepCopy();
    credentials = Map.copyOf(credentials);
    excludedEnvironment = Set.copyOf(excludedEnvironment);
  }

  /** 兼容未指定工具进程环境排除项的宿主。 */
  public HostServices(
      ConfigStore config, JsonNode settings, Map<String, ApiKeySource> credentials) {
    this(config, settings, credentials, Set.of());
  }

  /** 兼容既有宿主和插件，保留共享配置存储注入入口。 */
  public HostServices(ConfigStore config) {
    this(config, null, Map.of());
  }

  /** 注入单个插件的配置快照及按操作命名的凭据来源，便于装载时完成校验。 */
  public static HostServices inputs(JsonNode settings, Map<String, ApiKeySource> credentials) {
    return new HostServices(null, settings, credentials);
  }

  /** 同时传入工具子进程应剔除的变量名，凭据值继续由宿主保管。 */
  public static HostServices inputs(
      JsonNode settings, Map<String, ApiKeySource> credentials, Set<String> excludedEnvironment) {
    return new HostServices(null, settings, credentials, excludedEnvironment);
  }

  /** 创建空宿主服务面，供无需装配输入的插件使用。 */
  public static HostServices none() {
    return new HostServices(null);
  }

  /** 兼容旧宿主提供的共享配置存储，值可能为空。 */
  @Override
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "兼容旧入口，配置存储按约定由宿主共享。")
  public ConfigStore config() {
    return config;
  }

  /** 返回兼容配置存储，供既有插件使用。 */
  public Optional<ConfigStore> configOpt() {
    return Optional.ofNullable(config);
  }

  /** 返回独立的配置快照副本，插件对字段的修改仅影响本次副本。 */
  @Override
  public JsonNode settings() {
    return settings == null ? null : settings.deepCopy();
  }

  /** 返回为插件选定的配置快照（若有）。 */
  public Optional<JsonNode> settingsOpt() {
    return Optional.ofNullable(settings());
  }

  /** 按插件约定的操作名取得凭据来源。 */
  public Optional<ApiKeySource> credential(String name) {
    return Optional.ofNullable(credentials.get(name));
  }

  @Override
  public String toString() {
    return "HostServices[configPresent="
        + (config != null)
        + ", settingsPresent="
        + (settings != null)
        + ", credentialCount="
        + credentials.size()
        + "]";
  }
}
