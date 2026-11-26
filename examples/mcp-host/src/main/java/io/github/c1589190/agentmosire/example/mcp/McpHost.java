package io.github.c1589190.agentmosire.example.mcp;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import io.github.c1589190.agentmosire.example.catalog.CatalogTools;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolRegistry;
import io.github.c1589190.agentmosire.tool.mcp.McpHttpSecurity;
import io.github.c1589190.agentmosire.tool.mcp.McpPublisher;

@SpringBootApplication
public class McpHost {
    public static void main(String[] args) {
        SpringApplication.run(McpHost.class, args);
    }

    @Bean
    @ConditionalOnProperty(name = "mosire.mcp.enabled", havingValue = "true")
    McpPublisher catalogPublisher(@Value("${mosire.mcp.allowed-host}") String allowedHost) {
        var registry = new ToolRegistry(List.of(CatalogTools.module()));
        var policy = new RolePolicy(caller -> caller.principal().equals("alice")
                ? Set.of("reader", "private-reader") : Set.of("reader"), List.of(
                        new RolePolicy.Grant("reader", Set.of(CatalogTools.FIND), resource -> resource.id().equals("public")),
                        new RolePolicy.Grant("private-reader", Set.of(CatalogTools.FIND), resource -> resource.id().equals("private"))));
        var options = new McpPublisher.Options("agentmosire-catalog", "0.1.0-alpha1", "/mcp", "catalog.read",
                List.of(allowedHost), List.of());
        return new McpPublisher(options, new ToolInvoker(registry, policy), Set.of(CatalogTools.FIND));
    }

    @Bean
    @ConditionalOnProperty(name = "mosire.mcp.enabled", havingValue = "true")
    RouterFunction<ServerResponse> catalogRoutes(McpPublisher publisher) {
        return publisher.routes();
    }

    @Bean
    @ConditionalOnProperty(name = "mosire.mcp.enabled", havingValue = "true")
    SecurityFilterChain catalogSecurity(HttpSecurity http, McpPublisher publisher,
            @Value("${mosire.mcp.resource-uri}") URI resource,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") URI issuer,
            JwtDecoder decoder) throws Exception {
        return McpHttpSecurity.configure(http, publisher.options(), resource, issuer, decoder);
    }
}
