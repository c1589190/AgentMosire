package io.github.c1589190.agentmosire.example.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import io.github.c1589190.agentmosire.example.catalog.CatalogTools;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolImage;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolRegistry;
import io.github.c1589190.agentmosire.tool.ToolResult;
import io.github.c1589190.agentmosire.tool.mcp.McpHttpSecurity;
import io.github.c1589190.agentmosire.tool.mcp.McpPublisher;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {McpHost.class, McpHostTest.Tokens.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.address=127.0.0.1",
                "mosire.mcp.enabled=true",
                "mosire.mcp.allowed-host=127.0.0.1:*",
                "mosire.mcp.resource-uri=https://catalog.example.test/mcp",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test"
        })
class McpHostTest {
    private static final ToolId SECOND = new ToolId("second", "who");
    @LocalServerPort
    int port;
    @Autowired
    SecondPublisher second;

    @Test
    void officialClientUsesVerifiedSubjectAndDiscoversOnlyExportedTools() {
        try (var alice = client("alice"); var bob = client("bob")) {
            alice.initialize();
            bob.initialize();
            assertEquals(java.util.List.of(CatalogTools.FIND.externalName()),
                    alice.listTools().tools().stream().map(McpSchema.Tool::name).toList());
            var success = alice.callTool(request(CatalogTools.FIND.externalName(), "private"));
            assertFalse(success.isError());
            assertEquals("OK", ((Map<?, ?>) success.structuredContent()).get("code"));

            var denial = bob.callTool(request(CatalogTools.FIND.externalName(), "private"));
            assertTrue(denial.isError());
            assertEquals("DENIED", ((Map<?, ?>) denial.structuredContent()).get("code"));
            assertFalse(bob.callTool(request(CatalogTools.FIND.externalName(), "public")).isError());
            assertThrows(McpError.class, () -> alice.callTool(request(CatalogTools.INTERNAL.externalName(), "public")));
        }
    }

    @Test
    void transportRequiresScopeAndRejectsOtherOrigins() throws Exception {
        assertEquals(401, post(null, null).statusCode());
        assertEquals(401, post("wrong-audience", null).statusCode());
        assertEquals(403, post("no-scope", null).statusCode());
        assertEquals(403, post("alice", "https://untrusted.example.test").statusCode());
    }

    @Test
    void protectedResourceMetadataAdvertisesHostIssuerAndScope() throws Exception {
        URI address = URI.create(base() + "/.well-known/oauth-protected-resource/mcp");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(address).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("https://catalog.example.test/mcp"), response.body());
        assertTrue(response.body().contains("https://issuer.example.test"), response.body());
        assertTrue(response.body().contains("catalog.read"), response.body());
    }

    @Test
    void twoPublishersIsolateDiscoveryAuthenticationToolsAndLifecycle() throws Exception {
        String secondPath = "/second";
        String firstMetadata = "https://catalog.example.test/.well-known/oauth-protected-resource/mcp";
        String secondMetadata = "https://catalog.example.test/.well-known/oauth-protected-resource/second";
        assertTrue(post("/mcp", null, null).headers().firstValue("WWW-Authenticate").orElseThrow()
                .contains(firstMetadata));
        assertTrue(post(secondPath, null, null).headers().firstValue("WWW-Authenticate").orElseThrow()
                .contains(secondMetadata));
        assertEquals(401, post(secondPath, "alice", null).statusCode());
        assertEquals(401, post("/mcp", "second", null).statusCode());
        var noScope = post(secondPath, "second-no-scope", null);
        assertEquals(403, noScope.statusCode());
        String scopeChallenge = noScope.headers().firstValue("WWW-Authenticate").orElseThrow();
        assertTrue(scopeChallenge.contains(secondMetadata));
        assertTrue(scopeChallenge.contains("scope=\"second.read\""));
        URI address = URI.create(base() + "/.well-known/oauth-protected-resource/second");
        var metadata = HttpClient.newHttpClient().send(HttpRequest.newBuilder(address).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("https://catalog.example.test/second", JsonMapper.builder().build().readTree(metadata.body())
                .path("resource").asString());
        try (var first = client("alice"); var other = client("second", secondPath)) {
            first.initialize();
            other.initialize();
            assertEquals(List.of(SECOND.externalName()), other.listTools().tools().stream().map(McpSchema.Tool::name).toList());
            assertThrows(McpError.class, () -> other.callTool(request(CatalogTools.FIND.externalName(), "public")));
            var response = other.callTool(McpSchema.CallToolRequest.builder().name(SECOND.externalName())
                    .arguments(Map.of()).build());
            assertEquals("second:/second", ((McpSchema.TextContent) response.content().getFirst()).text());
            var image = assertInstanceOf(McpSchema.ImageContent.class, response.content().getLast());
            assertEquals("image/png", image.mimeType());
            assertEquals("AQID", image.data());
            second.close();
            assertEquals(503, post(secondPath, "second", null).statusCode());
            assertEquals(List.of(CatalogTools.FIND.externalName()),
                    first.listTools().tools().stream().map(McpSchema.Tool::name).toList());
        }
    }

    private HttpResponse<String> post(String token, String origin) throws Exception {
        return post("/mcp", token, origin);
    }

    private HttpResponse<String> post(String path, String token, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private McpSyncClient client(String token) {
        return client(token, "/mcp");
    }

    private McpSyncClient client(String token, String path) {
        var transport = HttpClientStreamableHttpTransport.builder(base()).endpoint(path)
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private static McpSchema.CallToolRequest request(String tool, String item) {
        return McpSchema.CallToolRequest.builder().name(tool).arguments(Map.of("item", item)).build();
    }

    @TestConfiguration
    static class Tokens {
        @Bean
        SecondPublisher secondPublisher() {
            var tool = new ToolDefinition(SECOND, "Return caller scope", "{\"type\":\"object\"}", true,
                    args -> List.of(), (caller, args) -> ToolDefinition.Verdict.ALLOW,
                    (args, access) -> ToolResult.okImage(access.caller().principal() + ":" + access.caller().scope(),
                            new ToolImage("image/png", new byte[] {1, 2, 3})));
            var policy = new RolePolicy(caller -> Set.of("second"),
                    List.of(new RolePolicy.Grant("second", Set.of(SECOND), resource -> true)));
            var options = new McpPublisher.Options("second", "1", "/second", "second.read",
                    List.of("127.0.0.1:*"), List.of());
            var registry = new ToolRegistry(List.of(new ToolModule("second", List.of(tool))));
            return new SecondPublisher(new McpPublisher(options, new ToolInvoker(registry, policy), Set.of(SECOND)));
        }

        @Bean
        RouterFunction<ServerResponse> secondRoutes(SecondPublisher second) {
            return second.publisher().routes();
        }

        @Bean
        SecurityFilterChain secondSecurity(HttpSecurity http, SecondPublisher second, JwtDecoder decoder) throws Exception {
            return McpHttpSecurity.configure(http, second.publisher().options(),
                    URI.create("https://catalog.example.test/second"), URI.create("https://issuer.example.test"), decoder);
        }

        @Bean
        JwtDecoder testJwtDecoder() {
            return value -> {
                if (!Set.of("alice", "bob", "no-scope", "wrong-audience", "second", "second-no-scope").contains(value)) {
                    throw new BadJwtException("Unrecognized test bearer token");
                }
                String scope = switch (value) {
                    case "no-scope", "second-no-scope" -> "other";
                    case "second" -> "second.read";
                    default -> "catalog.read";
                };
                String audience = switch (value) {
                    case "wrong-audience" -> "https://other.example.test/mcp";
                    case "second", "second-no-scope" -> "https://catalog.example.test/second";
                    default -> "https://catalog.example.test/mcp";
                };
                return Jwt.withTokenValue(value).header("alg", "RS256").subject(value)
                        .claim("scope", scope).audience(List.of(audience))
                        .issuer("https://issuer.example.test")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
            };
        }
    }

    record SecondPublisher(McpPublisher publisher) implements AutoCloseable {
        @Override
        public void close() {
            publisher.close();
        }
    }
}
