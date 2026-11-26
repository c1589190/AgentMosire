package io.github.c1589190.agentmosire.tool.mcp;

import java.net.URI;
import java.util.Objects;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** Builds a security chain scoped to an explicitly published MCP endpoint. */
public final class McpHttpSecurity {
    private static final String METADATA = "/.well-known/oauth-protected-resource";

    private McpHttpSecurity() {
    }

    public static SecurityFilterChain configure(HttpSecurity http, McpPublisher.Options options,
            URI resource, URI authorizationServer, JwtDecoder trustedDecoder) throws Exception {
        requireHttpUri(resource);
        requireHttpUri(authorizationServer);
        if (!options.endpoint().equals(resource.getPath())) {
            throw new IllegalArgumentException("The public resource URI must match the MCP endpoint path");
        }
        Objects.requireNonNull(trustedDecoder);
        JwtDecoder boundDecoder = encoded -> {
            var token = trustedDecoder.decode(encoded);
            if (!authorizationServer.toString().equals(token.getClaimAsString("iss"))
                    || token.getAudience() == null || !token.getAudience().contains(resource.toString())) {
                throw new BadJwtException("The bearer token does not target this MCP resource");
            }
            return token;
        };
        String metadataPath = METADATA + resource.getPath();
        return http.securityMatcher(options.endpoint(), METADATA, metadataPath)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers(options.endpoint()))
                .authorizeHttpRequests(auth -> auth.requestMatchers(METADATA, metadataPath).permitAll()
                        .anyRequest().hasAuthority("SCOPE_" + options.requiredScope()))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(boundDecoder))
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                builder -> builder.resource(resource.toString())
                                        .authorizationServer(authorizationServer.toString())
                                        .scope(options.requiredScope()).bearerMethod("header"))))
                .build();
    }

    private static void requireHttpUri(URI uri) {
        String scheme = Objects.requireNonNull(uri).getScheme();
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Expected an HTTP resource URI without credentials, query or fragment");
        }
    }
}
