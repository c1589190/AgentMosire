package io.github.c1589190.agentmosire.tool;

import java.util.Objects;
import java.util.UUID;

/** Identity data prepared by the host, never decoded from tool arguments. */
public record InvocationContext(String principal, String scope, UUID requestId) {
    public InvocationContext {
        if (Objects.requireNonNull(principal).isBlank() || Objects.requireNonNull(scope).isBlank()) {
            throw new IllegalArgumentException("Principal and scope are required");
        }
        Objects.requireNonNull(requestId);
    }

    public static InvocationContext create(String principal, String scope) {
        return new InvocationContext(principal, scope, UUID.randomUUID());
    }
}
