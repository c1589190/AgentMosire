// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.agent.AgentConfig;
import io.github.c1589190.agentmosire.agentlib.llm.ContentPart;
import io.github.c1589190.agentmosire.agentlib.llm.LlmRequest;
import io.github.c1589190.agentmosire.agentlib.llm.LlmResponse;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.permission.ResourceScope;
import io.github.c1589190.agentmosire.agentlib.permission.ResourceScopeMap;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolCallAuthorizer;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolExecutionGuard;
import io.github.c1589190.agentmosire.agentlib.tool.ToolRegistry;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.agentmosire.brain.runtime.SimpleAgent;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SearchToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final URI PAGE = URI.create("https://Docs.Example.com/article");
  private static final String QUERY = "协议示例";

  @Test
  void simpleAgentPassesToolResultsBackToTheModelThroughBothProviderInterfaces() {
    AtomicReference<SearchRequest> searched = new AtomicReference<>();
    AtomicReference<FetchRequest> fetched = new AtomicReference<>();
    ToolRegistry tools =
        registry(
            request -> {
              searched.set(request);
              return List.of(new SearchHit(PAGE, "标题", "摘要", "2026-11-22"));
            },
            request -> {
              fetched.set(request);
              return new FetchedPage("标题", "# 正文");
            });
    AtomicInteger rounds = new AtomicInteger();
    var agent =
        SimpleAgent.create(
            AgentConfig.builder("web-sdk").maxTurns(4).build(),
            request ->
                switch (rounds.getAndIncrement()) {
                  case 0 -> LlmResponse.toolCall("s-1", "web_search", Map.of("query", QUERY));
                  case 1 -> {
                    ContentPart.ToolResult result = toolResults(request).getFirst();
                    assertEquals("s-1", result.toolCallId());
                    assertTrue(result.content().contains(PAGE.toASCIIString()));
                    yield LlmResponse.toolCall("f-1", "web_fetch", Map.of("url", PAGE.toString()));
                  }
                  case 2 -> {
                    ContentPart.ToolResult result = toolResults(request).getLast();
                    assertEquals("f-1", result.toolCallId());
                    assertTrue(result.content().contains("# 正文"));
                    yield LlmResponse.text("已取回正文");
                  }
                  default -> throw new AssertionError("意外的模型往返");
                },
            tools,
            permissions(ResourceScope.unlimited()));

    assertEquals("已取回正文", agent.chat("找到网页并取正文").finalText().orElseThrow());
    assertEquals(new SearchRequest(QUERY, 10), searched.get());
    assertEquals(new FetchRequest(PAGE), fetched.get());
    assertEquals(3, rounds.get());
  }

  @Test
  void toolAndResourceDenialsAndInvalidInputsNeverReachProviders() {
    AtomicInteger calls = new AtomicInteger();
    ToolRegistry tools =
        registry(
            request -> {
              calls.incrementAndGet();
              return List.of();
            },
            request -> {
              calls.incrementAndGet();
              return new FetchedPage("固定标题", "固定正文");
            });
    var noTools = AgentPermissionSet.builder(AccessToken.DEFAULT).build();
    assertEquals(
        ToolExecutionGuard.DENIED,
        execute(tools, "web_search", noTools, Map.of("query", QUERY)).code());
    assertEquals(
        ToolCallAuthorizer.RESOURCE_DENIED,
        execute(tools, "web_search", permissions(ResourceScope.none()), Map.of("query", QUERY))
            .code());
    var deniedHost =
        execute(
            tools,
            "web_fetch",
            permissions(ResourceScope.of("fetch/other.example.com")),
            Map.of("url", PAGE.toString()));
    assertEquals(ToolCallAuthorizer.RESOURCE_DENIED, deniedHost.code());
    assertFalse(deniedHost.message().contains("docs.example.com"));
    assertEquals(
        "WEB_SCOPE_UNSUPPORTED",
        execute(
                tools,
                "web_fetch",
                permissions(ResourceScope.of("fetch/docs.example.com")),
                Map.of("url", PAGE.toString()))
            .code());
    var authorized = permissions(ResourceScope.unlimited());
    for (String url :
        List.of(
            "file:///tmp/page",
            "http://127.0.0.1",
            "http://host.local",
            "https://user:pass@docs.example.com",
            "http://docs.example.com:0")) {
      assertEquals(
          "WEB_INVALID_REQUEST",
          execute(tools, "web_fetch", authorized, Map.of("url", url)).code());
    }
    assertEquals(
        "WEB_INVALID_REQUEST",
        execute(tools, "web_search", authorized, Map.of("query", QUERY, "count", 1.5)).code());
    assertEquals(0, calls.get());
  }

  @Test
  void searchDropsWholeRowsWhenTheSerializedResponseReachesTheBridgeBudget() throws Exception {
    var hit = new SearchHit(PAGE, "标题", "\n".repeat(600), null);
    var tools = registry(request -> java.util.Collections.nCopies(request.count(), hit), null);
    var result =
        execute(
            tools,
            "web_search",
            permissions(ResourceScope.unlimited()),
            Map.of("query", QUERY, "count", 50));
    assertNull(result.code());
    assertTrue(result.message().length() <= 8000);
    JsonNode json = JSON.readTree(result.message());
    assertTrue(json.path("truncated").asBoolean());
    assertTrue(json.path("results").size() > 0);
    for (JsonNode row : json.path("results")) {
      assertEquals(PAGE.toASCIIString(), row.path("url").asText());
      assertEquals(hit.snippet(), row.path("snippet").asText());
    }
  }

  @Test
  void aHostConfinedProviderCanFetchWithinScopeAndOutputsBoundedMarkdown() throws Exception {
    String marker = "a\n😀".repeat(4000);
    var tools =
        registry(
            null,
            new FetchProvider() {
              @Override
              public FetchedPage fetch(FetchRequest request) {
                assertEquals("docs.example.com", request.host());
                return new FetchedPage("标题", marker + "😀还有正文");
              }

              @Override
              public boolean confinesToRequestedHost() {
                return true;
              }
            });
    var permitted = permissions(ResourceScope.of("fetch/docs.example.com"));
    var result = execute(tools, "web_fetch", permitted, Map.of("url", PAGE.toString()));
    assertNull(result.code());
    JsonNode page = JSON.readTree(result.message());
    assertEquals(PAGE.toASCIIString(), page.path("url").asText());
    String content = page.path("content").asText();
    assertTrue(content.length() > 1000);
    assertTrue(marker.startsWith(content));
    assertFalse(Character.isHighSurrogate(content.charAt(content.length() - 1)));
    assertTrue(result.message().length() <= 8000);
    assertTrue(page.path("truncated").asBoolean());
    assertEquals(
        List.of("web_fetch"), tools.listBySource("web").stream().map(AgentTool::name).toList());
    var tool = tools.find("web_fetch").orElseThrow();
    assertTrue(tool.summarizeLedgerResult());
    assertEquals(
        Map.of("urlLength", PAGE.toString().length()),
        tool.ledgerArgs(
            new ToolContext(
                AccessToken.DEFAULT, permitted, Map.of(), Map.of("url", PAGE.toString()))));
  }

  private static List<ContentPart.ToolResult> toolResults(LlmRequest request) {
    return request.messages().stream()
        .flatMap(message -> message.content().stream())
        .filter(ContentPart.ToolResult.class::isInstance)
        .map(ContentPart.ToolResult.class::cast)
        .toList();
  }

  private static ToolRegistry registry(SearchProvider search, FetchProvider fetch) {
    var registry = new ToolRegistry();
    registry.registerAll(SearchTools.SOURCE_ID, SearchTools.create(search, fetch));
    return registry;
  }

  private static AgentPermissionSet permissions(ResourceScope scope) {
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allow("web_search", "web_fetch")
        .resourceScopes(ResourceScopeMap.of("web", scope))
        .build();
  }

  private static ToolResult execute(
      ToolRegistry tools, String name, AgentPermissionSet permissions, Map<String, Object> args) {
    return ToolCallAuthorizer.standard()
        .execute(tools, name, new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args));
  }
}
