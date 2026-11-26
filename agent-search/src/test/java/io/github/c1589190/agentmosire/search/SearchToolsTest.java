package io.github.c1589190.agentmosire.search;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolRegistry;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchToolsTest {
    @Test
    void badHitKeepsGoodSourcesAndFetchRequiresEgressPermission() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tools/search", exchange -> {
            try (exchange) {
                byte[] body = """
                        {"results":[{"url":"https://www.example.com/#bad|","title":"bad","snippet":"bad"},
                        {"url":"https://www.example.com/ref","title":"reference","snippet":"a source"}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (var web = DeepSeekWebClient.forLoopback(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                () -> "local-probe", Duration.ofSeconds(5), 4096)) {
            var module = SearchTools.module(web, web);
            var policy = new RolePolicy(caller -> Set.of("restricted"), List.of(new RolePolicy.Grant("restricted",
                    Set.of(SearchTools.SEARCH, SearchTools.FETCH),
                    r -> r.action().equals("search") || r.id().equals("www.example.com"))));
            var invoker = new ToolInvoker(new ToolRegistry(List.of(module)), policy);
            var caller = InvocationContext.create("test", "search");
            var search = invoker.invoke(SearchTools.SEARCH, Map.of("query", "history"), caller);
            assertEquals(OK, search.code());
            assertEquals(1, search.data().get("skipped"));
            assertEquals(List.of(Map.of("url", "https://www.example.com/ref", "title", "reference", "snippet", "a source")),
                    search.data().get("results"));
            assertEquals(DENIED, invoker.invoke(SearchTools.FETCH, Map.of("url", "https://www.example.com/ref"), caller).code());
            assertEquals(INVALID_ARGUMENTS, invoker.invoke(SearchTools.FETCH, Map.of("url", "http://127.0.0.1/"), caller).code());
        } finally {
            server.stop(0);
        }
    }
}
