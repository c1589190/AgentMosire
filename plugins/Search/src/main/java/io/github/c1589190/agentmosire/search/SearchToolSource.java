// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.llm.OpenAICompatibleLlmClient.ApiKeySource;
import io.github.c1589190.agentmosire.agentlib.plugin.HostServices;
import io.github.c1589190.agentmosire.agentlib.plugin.ToolSource;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.pf4j.Extension;

/** 装载时按宿主注入的操作凭据创建网页工具。 */
@Extension
public final class SearchToolSource implements ToolSource {

  private static final Pattern ENVIRONMENT_KEY = Pattern.compile("env:[A-Za-z_][A-Za-z0-9_]*");
  private List<DeepSeekWebClient> clients = List.of();
  private List<AgentTool> tools = List.of();

  /** PF4J 入口，宿主在装载时注入启用操作的凭据来源。 */
  public SearchToolSource() {}

  @Override
  public String id() {
    return SearchTools.SOURCE_ID;
  }

  @Override
  public synchronized void init(HostServices services) {
    if (!clients.isEmpty()) {
      throw new IllegalStateException("网页工具已初始化");
    }
    ApiKeySource searchKeys = checkKey(credential(services, "search"), "search");
    ApiKeySource fetchKeys = checkKey(credential(services, "fetch"), "fetch");
    if (searchKeys == null && fetchKeys == null) {
      return;
    }
    List<DeepSeekWebClient> created = new ArrayList<>(2);
    boolean initialized = false;
    try {
      DeepSeekWebClient search = createClient(searchKeys, created);
      DeepSeekWebClient fetch = createClient(fetchKeys, created);
      tools = SearchTools.create(search, fetch);
      clients = List.copyOf(created);
      initialized = true;
    } finally {
      if (!initialized) {
        created.forEach(DeepSeekWebClient::close);
      }
    }
  }

  private static ApiKeySource credential(HostServices services, String operation) {
    JsonNode root = services.settings();
    JsonNode settings = root == null ? null : root.get(operation);
    if (settings == null) {
      return services.credential(operation).orElse(null);
    }
    if (!settings.isObject()) {
      throw new IllegalArgumentException("Search 的 " + operation + " 配置必须是对象");
    }
    JsonNode enabled = settings.get("enabled");
    if (enabled != null && !enabled.isBoolean()) {
      throw new IllegalArgumentException("Search 的 " + operation + ".enabled 必须是布尔值");
    }
    if (enabled != null && !enabled.booleanValue()) {
      return null;
    }
    JsonNode reference = settings.path("credentialsRef");
    if (!reference.isTextual() || !ENVIRONMENT_KEY.matcher(reference.asText()).matches()) {
      throw new IllegalArgumentException("Search 的 " + operation + ".credentialsRef 必须采用 env:变量名");
    }
    String name = reference.asText().substring(4);
    return () -> Optional.ofNullable(System.getenv(name));
  }

  @Override
  public synchronized List<AgentTool> listTools() {
    return List.copyOf(tools);
  }

  @Override
  public AutoCloseable onChange(Runnable listener) {
    return () -> {};
  }

  @Override
  public synchronized void close() {
    tools = List.of();
    List<DeepSeekWebClient> closing = clients;
    clients = List.of();
    closing.forEach(DeepSeekWebClient::close);
  }

  private static ApiKeySource checkKey(ApiKeySource keys, String operation) {
    if (keys == null) {
      return null;
    }
    try {
      DeepSeekWebClient.requireKey(keys);
    } catch (WebProviderException e) {
      throw new IllegalArgumentException("tools.web." + operation + ".credentialsRef 的凭据缺失或无效");
    }
    return keys;
  }

  private static DeepSeekWebClient createClient(
      ApiKeySource keys, List<DeepSeekWebClient> created) {
    if (keys == null) {
      return null;
    }
    DeepSeekWebClient client = new DeepSeekWebClient(keys);
    created.add(client);
    return client;
  }
}
