package io.github.c1589190.agentmosire.runtime.decision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Trusted, scope-specific state for host-managed settlement. */
public interface DecisionStore {
    Optional<DecisionPackage> find(UUID id);

    /** Inserts a new package, returning false if the ID already exists. */
    boolean create(DecisionPackage snapshot);

    /**
     * Applies every update atomically if all expected snapshots still match.
     * If business changes and receipts are durable, their writes belong to the same
     * transaction or atomic business event. A store may project snapshots from such events.
     */
    boolean compareAndSet(List<Update> updates);

    record Update(DecisionPackage expected, DecisionPackage next) { }
}
