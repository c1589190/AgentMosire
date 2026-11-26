package io.github.c1589190.agentmosire.tool;

import java.util.List;
import java.util.Objects;

public record ToolModule(String id, List<ToolDefinition> tools) {
    public ToolModule {
        Objects.requireNonNull(id);
        tools = List.copyOf(tools);
        for (ToolDefinition tool : tools) {
            if (!id.equals(tool.id().module())) {
                throw new IllegalArgumentException("Tool " + tool.id() + " must belong to module " + id);
            }
        }
    }
}
