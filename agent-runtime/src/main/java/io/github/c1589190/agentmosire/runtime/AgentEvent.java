package io.github.c1589190.agentmosire.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.github.c1589190.agentmosire.tool.ToolResult;

public sealed interface AgentEvent {
    record Started(UUID sessionId, UUID turnId) implements AgentEvent { }

    record Text(UUID sessionId, UUID turnId, String text) implements AgentEvent { }

    record DecisionReady(UUID sessionId, UUID turnId, UUID packageId, int itemCount) implements AgentEvent { }

    record DecisionCommitted(UUID agentId, UUID packageId, UUID turnId, long version,
            String executionPrincipal, String submittedBy, Instant completedAt, Map<Integer, ToolResult.Code> outcomes)
            implements AgentEvent {
        public DecisionCommitted {
            outcomes = Map.copyOf(outcomes);
        }
    }

    record Ended(UUID sessionId, TurnResult result) implements AgentEvent { }
}
