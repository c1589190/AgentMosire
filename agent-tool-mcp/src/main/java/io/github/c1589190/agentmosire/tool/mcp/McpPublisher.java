package io.github.c1589190.agentmosire.tool.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolResult;

/** Opt-in Streamable HTTP publication using the Spring AI stateless WebMVC transport. */
public final class McpPublisher implements AutoCloseable {
    private static final String CALLER = McpPublisher.class.getName() + ".caller";

    public record Options(String name, String version, String endpoint, String requiredScope,
            List<String> allowedHosts, List<String> allowedOrigins) {
        public Options {
            if (Objects.requireNonNull(name).isBlank() || Objects.requireNonNull(version).isBlank()
                    || !Objects.requireNonNull(endpoint).matches("/[a-zA-Z0-9_/-]+")
                    || !Objects.requireNonNull(requiredScope).matches("[a-zA-Z0-9_.:-]+")) {
                throw new IllegalArgumentException("MCP name, version, endpoint and scope are required");
            }
            allowedHosts = List.copyOf(allowedHosts);
            allowedOrigins = List.copyOf(allowedOrigins);
            if (allowedHosts.isEmpty() || allowedHosts.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("At least one MCP host must be allowed");
            }
        }
    }

    private final Options options;
    private final McpStatelessSyncServer server;
    private final RouterFunction<ServerResponse> routes;

    public McpPublisher(Options options, ToolInvoker invoker, Set<ToolId> export) {
        this.options = Objects.requireNonNull(options);
        var mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        List<SyncToolSpecification> specs = Set.copyOf(export).stream().map(id -> {
            var definition = invoker.registry().find(id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown MCP export: " + id));
            if (!definition.exportable()) {
                throw new IllegalArgumentException("Tool is marked as internal: " + id);
            }
            return specification(definition, invoker, mapper);
        }).sorted(java.util.Comparator.comparing(spec -> spec.tool().name())).toList();

        var transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(mapper)
                .messageEndpoint(options.endpoint())
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedHosts(options.allowedHosts()).allowedOrigins(options.allowedOrigins()).build())
                .contextExtractor(request -> {
                    var authenticated = identity(request);
                    return authenticated == null ? McpTransportContext.EMPTY
                            : McpTransportContext.create(Map.of(CALLER,
                                    InvocationContext.create(authenticated.getToken().getSubject(), options.endpoint())));
                })
                .build();
        server = McpServer.sync(transport)
                .serverInfo(options.name(), options.version())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(specs)
                .build();
        routes = transport.getRouterFunction().filter((request, next) -> {
            var authenticated = identity(request);
            if (authenticated == null) {
                return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build();
            }
            if (authenticated.getAuthorities().stream()
                    .noneMatch(authority -> authority.getAuthority().equals("SCOPE_" + options.requiredScope()))) {
                return ServerResponse.status(HttpStatus.FORBIDDEN).build();
            }
            return next.handle(request);
        });
    }

    public Options options() {
        return options;
    }

    public RouterFunction<ServerResponse> routes() {
        return routes;
    }

    private static JwtAuthenticationToken identity(ServerRequest request) {
        return request.principal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .filter(token -> token.isAuthenticated() && token.getToken().getSubject() != null
                        && !token.getToken().getSubject().isBlank())
                .orElse(null);
    }

    private static SyncToolSpecification specification(ToolDefinition definition, ToolInvoker invoker,
            JacksonMcpJsonMapper mapper) {
        var tool = McpSchema.Tool.builder().name(definition.id().externalName())
                .description(definition.description()).inputSchema(mapper, definition.inputSchema()).build();
        return new SyncToolSpecification(tool, (context, request) -> {
            ToolResult result = context.get(CALLER) instanceof InvocationContext caller
                    ? invoker.invoke(definition.id(), request.arguments() == null ? Map.of() : request.arguments(),
                            caller)
                    : ToolResult.of(ToolResult.Code.DENIED, "A verified caller is required");
            return result(result);
        });
    }

    static McpSchema.CallToolResult result(ToolResult result) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(result.message())
                .structuredContent(Map.of("code", result.code().name(), "message", result.message(),
                        "data", result.data()))
                .isError(result.code() != ToolResult.Code.OK)
                .build();
    }

    @Override
    public void close() {
        server.closeGracefully();
    }
}
