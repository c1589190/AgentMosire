package io.github.c1589190.agentmosire.runtime.decision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.c1589190.agentmosire.tool.ToolId;

public record DecisionPackage(UUID id, UUID turnId, String proposer, long version, Instant expiresAt,
        State state, List<Item> items, Map<Integer, Verdict> verdicts, DecisionReceipt receipt) {
    public enum State { PENDING, DECIDED, EXECUTING, CLOSED, EXPIRED, CANCELLED }

    public record Item(int index, ToolId tool, Map<String, Object> arguments, String digest) { }

    public record Verdict(boolean approved, String approver) { }

    public DecisionPackage {
        items = List.copyOf(items);
        verdicts = Map.copyOf(verdicts);
    }
}
