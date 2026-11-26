package io.github.c1589190.agentmosire.tool;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public final class RolePolicy implements AccessPolicy {
    public record Grant(String role, Set<ToolId> tools, Predicate<Resource> resources) {
        public Grant {
            Objects.requireNonNull(role);
            tools = Set.copyOf(tools);
            Objects.requireNonNull(resources);
        }
    }

    private final Function<InvocationContext, Set<String>> roles;
    private final List<Grant> grants;

    public RolePolicy(Function<InvocationContext, Set<String>> roles, Collection<Grant> grants) {
        this.roles = Objects.requireNonNull(roles);
        this.grants = List.copyOf(grants);
    }

    @Override
    public boolean allowsTool(InvocationContext caller, ToolId tool) {
        Set<String> assigned = Set.copyOf(roles.apply(caller));
        return grants.stream().anyMatch(grant -> assigned.contains(grant.role()) && grant.tools().contains(tool));
    }

    @Override
    public boolean allowsResource(InvocationContext caller, ToolId tool, Resource resource) {
        Set<String> assigned = Set.copyOf(roles.apply(caller));
        return grants.stream().anyMatch(grant -> assigned.contains(grant.role())
                && grant.tools().contains(tool) && grant.resources().test(resource));
    }
}
