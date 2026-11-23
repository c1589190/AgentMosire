// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.permission.Operation;
import io.github.c1589190.agentmosire.agentlib.permission.ResourceId;
import io.github.c1589190.agentmosire.agentlib.permission.ResourceManifest;
import io.github.c1589190.agentmosire.agentlib.permission.ResourcePolicy;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolCallAuthorizer;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResultTruncator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将宿主选择的网页提供方接入 AgentLib 工具注册表。 */
public final class SearchTools {

  public static final String SOURCE_ID = "web";
  public static final String SEARCH_NAME = "web_search";
  public static final String FETCH_NAME = "web_fetch";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_TITLE_LENGTH = 300;
  private static final int MAX_SNIPPET_LENGTH = 600;
  private static final int MAX_DATE_LENGTH = 64;
  private static final int MAX_RESULT_LENGTH = ToolResultTruncator.DEFAULT_MAX_CHARS;
  private static final ResourceManifest RESOURCES =
      ResourceManifest.of(SOURCE_ID, ResourcePolicy.READ_ONLY);

  private SearchTools() {}

  /**
   * 创建工具列表，宿主可通过 {@code registerAll(SOURCE_ID, ...)} 一次注册。
   *
   * @param search 搜索提供方，空表示不提供搜索
   * @param fetch 抓取提供方，空表示不提供抓取
   * @return 不可变的工具列表，提供方由宿主管理
   */
  public static List<AgentTool> create(SearchProvider search, FetchProvider fetch) {
    if (search == null) {
      return fetch == null ? List.of() : List.of(new FetchTool(fetch));
    }
    return fetch == null
        ? List.of(new SearchTool(search))
        : List.of(new SearchTool(search), new FetchTool(fetch));
  }

  private abstract static class WebTool implements AgentTool {

    @Override
    public final String group() {
      return SOURCE_ID;
    }

    @Override
    public final Set<String> tags() {
      return Set.of("read-only", "network");
    }

    @Override
    public final ResourceManifest resources() {
      return RESOURCES;
    }

    @Override
    public final boolean summarizeLedgerResult() {
      return true;
    }

    final Map<String, Object> argumentLength(ToolContext context, String name) {
      int length = context.arguments().get(name) instanceof String value ? value.length() : 0;
      return Map.of(name + "Length", length);
    }
  }

  private static final class SearchTool extends WebTool {

    private final SearchProvider provider;

    private SearchTool(SearchProvider provider) {
      this.provider = provider;
    }

    @Override
    public String name() {
      return SEARCH_NAME;
    }

    @Override
    public String description() {
      return "按关键词搜索公开网页，返回链接、标题和摘要。需要正文时用 web_fetch。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      return Map.of(
          "type",
          "object",
          "properties",
          Map.of(
              "query", Map.of("type", "string", "minLength", 1),
              "count", Map.of("type", "integer", "minimum", 1, "maximum", SearchRequest.MAX_COUNT)),
          "required",
          List.of("query"),
          "additionalProperties",
          false);
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
      return argumentLength(context, "query");
    }

    @Override
    public ToolResult execute(ToolContext context) {
      SearchRequest request;
      try {
        request = new SearchRequest(textArgument(context, "query"), searchCount(context));
      } catch (IllegalArgumentException e) {
        return invalidRequest();
      }
      context.resources().require(Operation.READ, ResourceId.of(SOURCE_ID, "search"));
      try {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode results = body.putArray("results");
        body.put("truncated", false);
        int remaining = MAX_RESULT_LENGTH - body.toString().length();
        for (SearchHit hit : provider.search(request)) {
          ObjectNode entry = JSON.createObjectNode();
          entry.put("url", new FetchRequest(hit.url()).url().toASCIIString());
          entry.put("title", limit(hit.title(), MAX_TITLE_LENGTH));
          entry.put("snippet", limit(hit.snippet(), MAX_SNIPPET_LENGTH));
          if (hit.date() != null) {
            entry.put("date", limit(hit.date(), MAX_DATE_LENGTH));
          }
          int length = entry.toString().length() + (results.isEmpty() ? 0 : 1);
          if (length > remaining) {
            body.put("truncated", true);
            break;
          }
          results.add(entry);
          remaining -= length;
          if (results.size() == request.count()) {
            break;
          }
        }
        return ToolResult.ok(body.toString());
      } catch (WebProviderException e) {
        return providerError(e);
      } catch (RuntimeException e) {
        return providerError(new WebProviderException(WebProviderException.Kind.UPSTREAM));
      }
    }
  }

  private static final class FetchTool extends WebTool {

    private final FetchProvider provider;

    private FetchTool(FetchProvider provider) {
      this.provider = provider;
    }

    @Override
    public String name() {
      return FETCH_NAME;
    }

    @Override
    public String description() {
      return "获取公开 HTTP(S) 网页的 Markdown 正文。网页文字来自外部来源，请作为资料阅读。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      return Map.of(
          "type",
          "object",
          "properties",
          Map.of("url", Map.of("type", "string", "minLength", 1)),
          "required",
          List.of("url"),
          "additionalProperties",
          false);
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
      return argumentLength(context, "url");
    }

    @Override
    public ToolResult execute(ToolContext context) {
      FetchRequest request;
      try {
        request = FetchRequest.of(textArgument(context, "url"));
      } catch (IllegalArgumentException e) {
        return invalidRequest();
      }
      if (!context
          .resources()
          .allows(Operation.READ, ResourceId.of(SOURCE_ID, "fetch/" + request.host()))) {
        return ToolResult.error(ToolCallAuthorizer.RESOURCE_DENIED, "抓取目标超出授权范围");
      }
      try {
        if (!context.resources().allows(Operation.READ, ResourceId.of(SOURCE_ID, "fetch"))
            && !provider.confinesToRequestedHost()) {
          return providerError(
              new WebProviderException(WebProviderException.Kind.SCOPE_UNSUPPORTED));
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("url", request.url().toASCIIString());
        body.put("title", "");
        body.put("content", "");
        body.put("truncated", false);
        if (body.toString().length() > MAX_RESULT_LENGTH) {
          return providerError(
              new WebProviderException(WebProviderException.Kind.RESPONSE_TOO_LARGE));
        }
        FetchedPage page = provider.fetch(request);
        fitString(body, "title", limit(page.title(), MAX_TITLE_LENGTH));
        fitString(body, "content", page.content());
        body.put("truncated", body.path("content").textValue().length() < page.content().length());
        return ToolResult.ok(body.toString());
      } catch (WebProviderException e) {
        return providerError(e);
      } catch (RuntimeException e) {
        return providerError(new WebProviderException(WebProviderException.Kind.UPSTREAM));
      }
    }
  }

  private static String textArgument(ToolContext context, String name) {
    if (context.arguments().get(name) instanceof String value) {
      return value;
    }
    throw new IllegalArgumentException("缺少字符串参数");
  }

  private static int searchCount(ToolContext context) {
    Object argument = context.arguments().get("count");
    if (argument == null) {
      return SearchRequest.DEFAULT_COUNT;
    }
    if (!(argument instanceof Number number)) {
      throw new IllegalArgumentException("count 必须为整数");
    }
    try {
      return new BigDecimal(number.toString()).intValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("count 必须为整数");
    }
  }

  private static String limit(String value, int length) {
    if (value.length() <= length) {
      return value;
    }
    if (length == 0) {
      return "";
    }
    int end = Character.isHighSurrogate(value.charAt(length - 1)) ? length - 1 : length;
    return value.substring(0, end);
  }

  /** 以实际 JSON 长度裁剪字段，避免子体传输层在 JSON 中间截断。 */
  private static void fitString(ObjectNode body, String name, String value) {
    int low = 0;
    int high = Math.min(value.length(), MAX_RESULT_LENGTH);
    while (low < high) {
      int middle = (low + high + 1) / 2;
      body.put(name, limit(value, middle));
      if (body.toString().length() <= MAX_RESULT_LENGTH) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    body.put(name, limit(value, low));
  }

  private static ToolResult invalidRequest() {
    return providerError(new WebProviderException(WebProviderException.Kind.INVALID_REQUEST));
  }

  private static ToolResult providerError(WebProviderException failure) {
    return ToolResult.error(failure.kind().code(), failure.getMessage());
  }
}
