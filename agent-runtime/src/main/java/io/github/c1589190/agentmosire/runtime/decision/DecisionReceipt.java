package io.github.c1589190.agentmosire.runtime.decision;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.c1589190.agentmosire.tool.ToolResult;

public record DecisionReceipt(UUID packageId, UUID turnId, long version, String executionPrincipal,
        String submittedBy, Instant completedAt, Map<Integer, ToolResult> outcomes) {
    public DecisionReceipt {
        outcomes = Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
    }
}
