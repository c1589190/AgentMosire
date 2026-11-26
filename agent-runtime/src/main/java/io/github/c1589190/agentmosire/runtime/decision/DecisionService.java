package io.github.c1589190.agentmosire.runtime.decision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import io.github.c1589190.agentmosire.tool.JsonValues;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolResult;
import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage.Item;
import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage.State;
import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage.Verdict;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;

/** In-memory decision state for one agent. Identities are supplied by the trusted host. */
public final class DecisionService implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(DecisionService.class.getName());

    private final Map<UUID, Entry> packages = new ConcurrentHashMap<>();
    private final ToolInvoker invoker;
    private final BiPredicate<String, DecisionPackage> approvers;
    private final Clock clock;
    private final Executor executor;
    private final Consumer<DecisionReceipt> receiptListener;
    private volatile boolean closed;

    public DecisionService(ToolInvoker invoker, BiPredicate<String, DecisionPackage> approvers) {
        this(invoker, approvers, ignored -> { });
    }

    public DecisionService(ToolInvoker invoker, BiPredicate<String, DecisionPackage> approvers,
            Consumer<DecisionReceipt> receiptListener) {
        this(invoker, approvers, Clock.systemUTC(), Thread::startVirtualThread, receiptListener);
    }

    public DecisionService(ToolInvoker invoker, BiPredicate<String, DecisionPackage> approvers,
            Clock clock, Executor executor, Consumer<DecisionReceipt> receiptListener) {
        this.invoker = Objects.requireNonNull(invoker);
        this.approvers = Objects.requireNonNull(approvers);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.receiptListener = Objects.requireNonNull(receiptListener);
    }

    public synchronized DecisionPackage open(UUID turnId, List<ToolInvoker.DeferredCall> calls, Duration ttl) {
        Objects.requireNonNull(turnId);
        if (closed) {
            throw new IllegalStateException("The decision service is closed");
        }
        if (calls.isEmpty() || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("A decision package needs calls and a positive lifetime");
        }
        var proposer = calls.getFirst().caller();
        List<PendingItem> items = new ArrayList<>();
        for (var call : calls) {
            if (!proposer.equals(call.caller()) || !turnId.equals(call.caller().requestId())) {
                throw new IllegalArgumentException("All items must belong to the same turn and caller");
            }
            items.add(new PendingItem(new Item(items.size(), call.tool(), call.arguments(),
                    digest(call.arguments())), call));
        }
        Entry entry = new Entry(UUID.randomUUID(), turnId, proposer.principal(), clock.instant().plus(ttl),
                List.copyOf(items));
        DecisionPackage opened = entry.snapshot();
        packages.put(entry.id, entry);
        return opened;
    }

    public Optional<DecisionPackage> find(UUID id, String viewer) {
        Entry entry = packages.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            expire(entry);
            var snapshot = entry.snapshot();
            return canRead(viewer, snapshot) ? Optional.of(snapshot) : Optional.empty();
        }
    }

    /** Creates a new content version from a subset of the currently pending items. */
    public DecisionPackage retainItems(UUID id, long expectedVersion, String proposer, Set<Integer> retained) {
        Entry entry = requirePackage(id);
        synchronized (entry) {
            requireProposer(proposer, entry);
            requirePending(entry, expectedVersion);
            Set<Integer> indices = Set.copyOf(retained);
            if (indices.isEmpty() || !entry.indices().containsAll(indices)) {
                throw new IllegalArgumentException("A package must retain existing items");
            }
            if (indices.size() != entry.items.size()) {
                entry.items = entry.items.stream().filter(item -> indices.contains(item.item().index())).toList();
                entry.version++;
                entry.verdicts.clear();
            }
            return entry.snapshot();
        }
    }

    public DecisionPackage decideAll(UUID id, long expectedVersion, String approver, boolean approved) {
        Entry entry = requirePackage(id);
        synchronized (entry) {
            requireApprover(approver, entry);
            requirePending(entry, expectedVersion);
            Map<Integer, Boolean> votes = new LinkedHashMap<>();
            for (var item : entry.items) {
                votes.put(item.item().index(), approved);
            }
            applyVotes(entry, approver, votes);
            return entry.snapshot();
        }
    }

    public DecisionPackage decideItems(UUID id, long expectedVersion, String approver, Map<Integer, Boolean> votes) {
        Entry entry = requirePackage(id);
        synchronized (entry) {
            requireApprover(approver, entry);
            requirePending(entry, expectedVersion);
            applyVotes(entry, approver, Map.copyOf(votes));
            return entry.snapshot();
        }
    }

    public DecisionPackage cancel(UUID id, String requester) {
        Entry entry = requirePackage(id);
        synchronized (entry) {
            requireParticipant(requester, entry);
            expire(entry);
            cancel(entry);
            return entry.snapshot();
        }
    }

    @Override
    public void close() {
        List<Entry> owned;
        synchronized (this) {
            closed = true;
            owned = List.copyOf(packages.values());
        }
        for (Entry entry : owned) {
            synchronized (entry) {
                cancel(entry);
            }
        }
    }

    private static void cancel(Entry entry) {
        switch (entry.state) {
            case PENDING, DECIDED -> entry.state = State.CANCELLED;
            case EXECUTING -> entry.cancelled = true;
            default -> { }
        }
    }

    public CompletionStage<DecisionReceipt> commit(UUID id, String requester) {
        Entry entry = requirePackage(id);
        synchronized (entry) {
            requireParticipant(requester, entry);
            expire(entry);
            if (entry.state == State.EXECUTING || entry.state == State.CLOSED) {
                return entry.completion.minimalCompletionStage();
            }
            if (closed || entry.state != State.DECIDED) {
                throw new IllegalStateException("Cannot submit a package in state " + entry.state);
            }
            entry.submittedBy = requester;
            entry.state = State.EXECUTING;
        }
        try {
            executor.execute(() -> execute(entry));
        } catch (RuntimeException rejected) {
            synchronized (entry) {
                Map<Integer, ToolResult> outcomes = new LinkedHashMap<>();
                for (var item : entry.items) {
                    int index = item.item().index();
                    outcomes.put(index, entry.verdicts.get(index).approved()
                            ? ToolResult.of(FAILED, "The executor did not start")
                            : ToolResult.of(DENIED, "The item was not approved"));
                }
                close(entry, outcomes);
            }
        }
        return entry.completion.minimalCompletionStage();
    }

    private void execute(Entry entry) {
        Map<Integer, ToolResult> outcomes = new LinkedHashMap<>();
        for (var next : entry.items) {
            int index = next.item().index();
            ToolResult result;
            synchronized (entry) {
                result = !entry.verdicts.get(index).approved() ? ToolResult.of(DENIED, "The item was not approved")
                        : entry.cancelled ? ToolResult.of(CANCELLED, "Execution was cancelled") : null;
            }
            if (result == null) {
                try {
                    result = invoker.executeApproved(next.call());
                } catch (RuntimeException failure) {
                    result = ToolResult.of(UNKNOWN, "Execution could not be confirmed");
                }
            }
            outcomes.put(index, result);
        }
        close(entry, outcomes);
    }

    private void close(Entry entry, Map<Integer, ToolResult> outcomes) {
        DecisionReceipt receipt;
        synchronized (entry) {
            if (entry.state != State.EXECUTING) {
                return;
            }
            receipt = new DecisionReceipt(entry.id, entry.turnId, entry.version, entry.proposer,
                    entry.submittedBy, clock.instant(), outcomes);
            entry.receipt = receipt;
            entry.state = State.CLOSED;
        }
        entry.completion.complete(receipt);
        try {
            receiptListener.accept(receipt);
        } catch (RuntimeException listenerFailure) {
            LOGGER.log(System.Logger.Level.WARNING, "Decision receipt listener failed for {0}", entry.id);
        }
    }

    private void applyVotes(Entry entry, String approver, Map<Integer, Boolean> votes) {
        if (votes.isEmpty() || !entry.indices().containsAll(votes.keySet())) {
            throw new IllegalArgumentException("Verdicts must reference existing items");
        }
        votes.forEach((index, approved) -> {
            Verdict existing = entry.verdicts.get(index);
            if (existing != null && existing.approved() != approved) {
                throw new IllegalStateException("Item " + index + " already has a different verdict");
            }
        });
        votes.forEach((index, approved) -> entry.verdicts.putIfAbsent(index, new Verdict(approved, approver)));
        if (entry.verdicts.size() == entry.items.size()) {
            entry.state = State.DECIDED;
        }
    }

    private Entry requirePackage(UUID id) {
        return Optional.ofNullable(packages.get(id))
                .orElseThrow(() -> new IllegalArgumentException("Unknown decision package"));
    }

    private void requirePending(Entry entry, long expectedVersion) {
        expire(entry);
        if (entry.version != expectedVersion || entry.state != State.PENDING) {
            throw new IllegalStateException("Decision package is not pending at version " + expectedVersion);
        }
    }

    private void expire(Entry entry) {
        if ((entry.state == State.PENDING || entry.state == State.DECIDED)
                && !clock.instant().isBefore(entry.expiresAt)) {
            entry.state = State.EXPIRED;
        }
    }

    private boolean canRead(String viewer, DecisionPackage snapshot) {
        return viewer != null && (snapshot.proposer().equals(viewer) || approvers.test(viewer, snapshot));
    }

    private void requireParticipant(String requester, Entry entry) {
        if (!canRead(requester, entry.snapshot())) {
            throw new SecurityException("Decision package is outside the requester's scope");
        }
    }

    private void requireApprover(String approver, Entry entry) {
        if (approver == null || !approvers.test(approver, entry.snapshot())) {
            throw new SecurityException("Requester cannot approve this package");
        }
    }

    private static void requireProposer(String proposer, Entry entry) {
        if (!entry.proposer.equals(proposer)) {
            throw new SecurityException("Only the proposer may revise the package");
        }
    }

    private static String digest(Map<String, Object> arguments) {
        try {
            var bytes = JsonValues.write(arguments).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private record PendingItem(Item item, ToolInvoker.DeferredCall call) { }

    private static final class Entry {
        private final UUID id;
        private final UUID turnId;
        private final String proposer;
        private final Instant expiresAt;
        private final Map<Integer, Verdict> verdicts = new LinkedHashMap<>();
        private final CompletableFuture<DecisionReceipt> completion = new CompletableFuture<>();
        private List<PendingItem> items;
        private State state = State.PENDING;
        private long version = 1;
        private boolean cancelled;
        private String submittedBy;
        private DecisionReceipt receipt;

        private Entry(UUID id, UUID turnId, String proposer, Instant expiresAt, List<PendingItem> items) {
            this.id = id;
            this.turnId = turnId;
            this.proposer = proposer;
            this.expiresAt = expiresAt;
            this.items = items;
        }

        private Set<Integer> indices() {
            Set<Integer> indices = new HashSet<>();
            for (PendingItem pending : items) {
                indices.add(pending.item().index());
            }
            return indices;
        }

        private DecisionPackage snapshot() {
            return new DecisionPackage(id, turnId, proposer, version, expiresAt, state,
                    items.stream().map(PendingItem::item).toList(), verdicts, receipt);
        }
    }
}
