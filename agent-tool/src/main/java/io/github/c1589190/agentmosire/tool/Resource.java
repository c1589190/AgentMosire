package io.github.c1589190.agentmosire.tool;

import java.util.Objects;

public record Resource(String type, String id, String action) {
    public Resource {
        if (Objects.requireNonNull(type).isBlank() || Objects.requireNonNull(id).isBlank()
                || Objects.requireNonNull(action).isBlank()) {
            throw new IllegalArgumentException("Resource type, id and action are required");
        }
    }
}
