package io.github.c1589190.agentmosire.example.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.json.JsonMapper;

import io.github.c1589190.agentmosire.example.catalog.CatalogTools;

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
    @LocalServerPort
    int port;

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

    private HttpResponse<String> post(String token, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + "/mcp"))
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
        var transport = HttpClientStreamableHttpTransport.builder(base())
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
        JwtDecoder testJwtDecoder() {
            return value -> {
                if (!java.util.Set.of("alice", "bob", "no-scope", "wrong-audience").contains(value)) {
                    throw new BadJwtException("Unrecognized test bearer token");
                }
                return Jwt.withTokenValue(value).header("alg", "RS256").subject(value)
                        .claim("scope", value.equals("no-scope") ? "other" : "catalog.read")
                        .audience(java.util.List.of(value.equals("wrong-audience")
                                ? "https://other.example.test/mcp" : "https://catalog.example.test/mcp"))
                        .issuer("https://issuer.example.test")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
            };
        }
    }
}
