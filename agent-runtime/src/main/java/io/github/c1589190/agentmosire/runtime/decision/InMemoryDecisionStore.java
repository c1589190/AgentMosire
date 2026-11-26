package io.github.c1589190.agentmosire.runtime.decision;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** An in-process store for hosts that manage settlement without restart recovery. */
public final class InMemoryDecisionStore implements DecisionStore {
    private final Map<UUID, DecisionPackage> packages = new HashMap<>();

    @Override
    public synchronized Optional<DecisionPackage> find(UUID id) {
        return Optional.ofNullable(packages.get(id));
    }

    @Override
    public synchronized boolean create(DecisionPackage snapshot) {
        return packages.putIfAbsent(snapshot.id(), snapshot) == null;
    }

    @Override
    public synchronized boolean compareAndSet(List<Update> updates) {
        var ids = new HashSet<UUID>();
        for (var update : updates) {
            UUID id = update.expected().id();
            if (!id.equals(update.next().id()) || !ids.add(id)) {
                throw new IllegalArgumentException("Each update must target a distinct package");
            }
            if (!update.expected().equals(packages.get(id))) {
                return false;
            }
        }
        updates.forEach(update -> packages.put(update.next().id(), update.next()));
        return true;
    }
}
