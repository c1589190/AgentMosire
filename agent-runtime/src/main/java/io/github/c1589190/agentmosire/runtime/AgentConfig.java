package io.github.c1589190.agentmosire.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage;
import io.github.c1589190.agentmosire.tool.AccessPolicy;
import io.github.c1589190.agentmosire.tool.ToolId;

public record AgentConfig(String principal, Set<ToolId> tools, AccessPolicy policy, String instructions,
        int maxToolCalls, Duration turnTimeout, Duration decisionLifetime,
        BiPredicate<String, DecisionPackage> approvers, Consumer<AgentEvent> events) {

    public AgentConfig {
        if (Objects.requireNonNull(principal).isBlank()) {
            throw new IllegalArgumentException("Agent principal is required");
        }
        tools = Set.copyOf(tools);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(instructions);
        Objects.requireNonNull(approvers);
        Objects.requireNonNull(events);
        if (maxToolCalls < 1 || !positive(turnTimeout) || !positive(decisionLifetime)) {
            throw new IllegalArgumentException("Positive budgets and durations are required");
        }
    }

    public static AgentConfig of(String principal, Set<ToolId> tools, AccessPolicy policy,
            BiPredicate<String, DecisionPackage> approvers) {
        return new AgentConfig(principal, tools, policy, "", 40, Duration.ofMinutes(2), Duration.ofMinutes(15),
                approvers, event -> { });
    }

    private static boolean positive(Duration duration) {
        return !duration.isNegative() && !duration.isZero();
    }
}
