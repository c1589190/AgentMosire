package io.github.c1589190.agentmosire.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ToolDefinition(ToolId id, String description, String inputSchema, boolean exportable,
        Resources resources, Gate gate, Handler handler) {

    public enum Verdict { ALLOW, BLOCK, ASK }

    @FunctionalInterface
    public interface Resources {
        List<Resource> resolve(Map<String, Object> arguments);
    }

    @FunctionalInterface
    public interface Gate {
        Verdict evaluate(InvocationContext caller, Map<String, Object> arguments);
    }

    @FunctionalInterface
    public interface Handler {
        ToolResult execute(Map<String, Object> arguments, Access access);
    }

    public interface Access {
        InvocationContext caller();

        List<Resource> resources();

        void require(Resource resource);
    }

    public ToolDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(description);
        Objects.requireNonNull(inputSchema);
        Objects.requireNonNull(resources);
        Objects.requireNonNull(gate);
        Objects.requireNonNull(handler);
    }
}
