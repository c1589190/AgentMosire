// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.main.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.c1589190.agentmosire.agentlib.config.ConfigException;
import io.github.c1589190.agentmosire.agentlib.config.FileConfigStore;
import io.github.c1589190.agentmosire.agentlib.llm.ContentPart;
import io.github.c1589190.agentmosire.agentlib.llm.LlmClient;
import io.github.c1589190.agentmosire.agentlib.llm.LlmRequest;
import io.github.c1589190.agentmosire.agentlib.llm.LlmResponse;
import io.github.c1589190.agentmosire.agentlib.llm.LlmRouteAssembler;
import io.github.c1589190.agentmosire.agentlib.llm.LlmRouteLoader;
import io.github.c1589190.agentmosire.agentlib.llm.ModelCapabilities;
import io.github.c1589190.agentmosire.agentlib.llm.ModelRoute;
import io.github.c1589190.agentmosire.agentlib.llm.OpenAICompatibleLlmClient.ApiKeySource;
import io.github.c1589190.agentmosire.agentlib.llm.Sampling;
import io.github.c1589190.agentmosire.agentlib.llm.ToolAssetResolver;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 主进程与子进程共享的模型配置装配入口。 */
public final class LlmConfiguration {

  private static final String EFFORT_KEY = "reasoningEffort";
  private static final String PROMPT_KEY = "systemPrompt";
  private static final String WIRE_EFFORT = "reasoning_effort";

  private LlmConfiguration() {}

  /** 同一配置快照中的模型客户端、压缩参数、凭据来源和启动选项。 */
  public record Assembly(
      LlmClient client,
      ModelCapabilities capabilities,
      ApiKeySource keySource,
      Set<String> excludedEnvironment,
      RouteOptions options) {

    public Assembly {
      excludedEnvironment = Set.copyOf(excludedEnvironment);
      Objects.requireNonNull(options, "options");
    }

    /** 兼容未提供启动选项的调用方。 */
    public Assembly(
        LlmClient client,
        ModelCapabilities capabilities,
        ApiKeySource keySource,
        Set<String> excludedEnvironment) {
      this(client, capabilities, keySource, excludedEnvironment, RouteOptions.DEFAULT);
    }

    /** 兼容由调用方直接构造、未提供环境排除项的模型装配。 */
    public Assembly(LlmClient client, ModelCapabilities capabilities, ApiKeySource keySource) {
      this(client, capabilities, keySource, Set.of());
    }
  }

  /** 推理等级用于请求默认值，提示词仅供构造 Agent 时选择基础提示词。 */
  public record RouteOptions(String reasoningEffort, String systemPrompt) {

    public static final RouteOptions DEFAULT = new RouteOptions(null, null);

    @Override
    public String toString() {
      return "RouteOptions[reasoningEffortLength="
          + (reasoningEffort == null ? 0 : reasoningEffort.length())
          + ", systemPromptLength="
          + (systemPrompt == null ? 0 : systemPrompt.length())
          + "]";
    }
  }

  /** 主、子进程使用各自的宿主身份选择路由，共用配置解析和凭据绑定。 */
  public static Assembly load(FileConfigStore store, String ownerId) {
    return load(store, ownerId, null);
  }

  /** 子进程仅读父进程启动时已登记的环境凭据名称，独立启动时可沿用默认读取规则。 */
  public static Assembly load(
      FileConfigStore store, String ownerId, Set<String> allowedEnvironment) {
    return load(store, ownerId, allowedEnvironment, ToolAssetResolver.none());
  }

  /** 从同一份路由配置读取模型能力，并使用宿主限定的图片解析器。 */
  public static Assembly load(
      FileConfigStore store,
      String ownerId,
      Set<String> allowedEnvironment,
      ToolAssetResolver assetResolver) {
    JsonNode selected = ownerRoute(store, ownerId);
    JsonNode llm = llmFields(store);
    var resolved = LlmRouteLoader.resolve(llm, selected, ownerId);
    RouteOptions options = routeOptions(llm, resolved.route().name());
    String reference = resolved.route().credentialsRef();
    var environmentName = HostCredentials.environmentName(reference);
    if (allowedEnvironment != null
        && environmentName.isPresent()
        && !allowedEnvironment.contains(environmentName.get())) {
      throw new ConfigException("E_LLM_ENV_NOT_ALLOWED", "模型凭据引用的环境变量未在服务启动时登记，请重启服务");
    }
    ApiKeySource keySource = HostCredentials.source(store, reference);
    return new Assembly(
        withReasoningDefault(
            LlmRouteAssembler.client(
                resolved.route(), keySource, assetResolver, resolved.capabilities().vision()),
            options),
        resolved.capabilities(),
        keySource,
        HostCredentials.modelEnvironmentNames(llm),
        options);
  }

  /** 交互引导只检查是否已有有效路由，容量仍由运行时入口校验。 */
  public static void validateSelectedRoute(FileConfigStore store, String ownerId) {
    JsonNode selected = ownerRoute(store, ownerId);
    LlmRouteLoader.parseSelected(llmFields(store), selected, ownerId);
  }

  /** 以引导刚写下的显式路由创建客户端，供回环配置入口热切换。 */
  public static LlmClient clientForSetup(FileConfigStore store, String routeName) {
    return clientForSetup(store, routeName, ToolAssetResolver.none());
  }

  /** 引导热切换复用当前进程的资产解析器。 */
  public static LlmClient clientForSetup(
      FileConfigStore store, String routeName, ToolAssetResolver assetResolver) {
    JsonNode llm = llmFields(store);
    ModelRoute route = LlmRouteLoader.parse(llm, routeName);
    var selected =
        LlmRouteLoader.resolve(llm, TextNode.valueOf(route.name()), AgentIdentity.MAIN_ID);
    return withReasoningDefault(
        LlmRouteAssembler.client(
            route,
            HostCredentials.fileSource(store, route.credentialsRef()),
            assetResolver,
            selected.capabilities().vision()),
        routeOptions(llm, route.name()));
  }

  /** 读取已选路由的选项，也供连接引导保留曾设置的字段。 */
  public static RouteOptions routeOptions(JsonNode llm, String routeName) {
    if (llm == null) {
      return RouteOptions.DEFAULT;
    }
    JsonNode named = llm.path("routes").get(routeName);
    JsonNode fields = named;
    String path = "llm.routes." + routeName;
    if (named == null && "default".equals(routeName)) {
      fields = llm;
      path = "llm";
    }
    if (fields == null || !fields.isObject()) {
      return RouteOptions.DEFAULT;
    }
    String effort = optionalText(fields, path, EFFORT_KEY);
    return new RouteOptions(
        effort == null ? null : effort.strip(), optionalText(fields, path, PROMPT_KEY));
  }

  private static String optionalText(JsonNode fields, String path, String key) {
    JsonNode value = fields.get(key);
    if (value == null) {
      return null;
    }
    String text = value.textValue();
    if (text == null || text.isBlank()) {
      throw new ConfigException(LlmRouteLoader.E_LLM_CONFIG_MISSING, path + "." + key + " 必须是非空文本");
    }
    return text;
  }

  /** 用宿主提供的路由字段装配客户端，正式运行与引导探测共用请求等级默认值。 */
  public static LlmClient client(ModelRoute route, ApiKeySource keySource, RouteOptions options) {
    return withReasoningDefault(LlmRouteAssembler.client(route, keySource), options);
  }

  private static LlmClient withReasoningDefault(LlmClient client, RouteOptions options) {
    return options.reasoningEffort() == null
        ? client
        : new ReasoningDefault(client, options.reasoningEffort());
  }

  private record ReasoningDefault(LlmClient delegate, String effort) implements LlmClient {

    @Override
    public LlmResponse chat(LlmRequest request) {
      Sampling sampling = request.sampling();
      if (sampling.extraBody().containsKey(WIRE_EFFORT)) {
        return delegate.chat(request);
      }
      var body = new HashMap<>(sampling.extraBody());
      body.put(WIRE_EFFORT, effort);
      return delegate.chat(
          request.withSampling(new Sampling(sampling.temperature(), sampling.maxTokens(), body)));
    }

    @Override
    public String model() {
      return delegate.model();
    }

    @Override
    public boolean supportsVision() {
      return delegate.supportsVision();
    }

    @Override
    public void validateImages(List<ContentPart.Image> images) {
      delegate.validateImages(images);
    }

    @Override
    public String toString() {
      return "ReasoningDefault[effortLength=" + effort.length() + "]";
    }
  }

  private static JsonNode llmFields(FileConfigStore store) {
    return store.globalSection("llm").orElse(null);
  }

  private static JsonNode ownerRoute(FileConfigStore store, String ownerId) {
    Objects.requireNonNull(ownerId, "ownerId");
    return store.get("agents." + ownerId, "llm.route").orElse(null);
  }
}
