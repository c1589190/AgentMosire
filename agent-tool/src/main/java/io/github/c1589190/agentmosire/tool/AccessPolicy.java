package io.github.c1589190.agentmosire.tool;

/** Implementations may evaluate current grants on every invocation. */
public interface AccessPolicy {
    boolean allowsTool(InvocationContext caller, ToolId tool);

    boolean allowsResource(InvocationContext caller, ToolId tool, Resource resource);
}
