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

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.JsonValues;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolResult;
import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage.Item;
import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage.State;
import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage.Verdict;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;

/** Decision state for one agent or a host-managed scope. Identities come from the trusted host. */
public final class DecisionService implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(DecisionService.class.getName());

    private final Map<UUID, Entry> packages = new ConcurrentHashMap<>();
    private final ToolInvoker invoker;
    private final BiPredicate<String, DecisionPackage> approvers;
    private final Clock clock;
    private final Executor executor;
    private final Consumer<DecisionReceipt> receiptListener;
    private final DecisionStore store;
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
        this(invoker, approvers, clock, executor, receiptListener, null);
    }

    /** Enables host settlement and stores package snapshots independently of the agent lifetime. */
    public DecisionService(ToolInvoker invoker, BiPredicate<String, DecisionPackage> approvers,
            DecisionStore store) {
        this(invoker, approvers, Clock.systemUTC(), Thread::startVirtualThread, ignored -> { },
                Objects.requireNonNull(store));
    }

    private DecisionService(ToolInvoker invoker, BiPredicate<String, DecisionPackage> approvers,
            Clock clock, Executor executor, Consumer<DecisionReceipt> receiptListener, DecisionStore store) {
        this.invoker = Objects.requireNonNull(invoker);
        this.approvers = Objects.requireNonNull(approvers);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.receiptListener = Objects.requireNonNull(receiptListener);
        this.store = store;
    }

    public synchronized DecisionPackage open(UUID turnId, List<ToolInvoker.DeferredCall> calls, Duration ttl) {
        Objects.requireNonNull(turnId);
        calls = List.copyOf(calls);
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
        invoker.claimForPackage(calls);
        Entry entry = new Entry(UUID.randomUUID(), proposer, clock.instant().plus(ttl),
                List.copyOf(items));
        DecisionPackage opened = entry.snapshot();
        if (store == null) {
            packages.put(entry.id, entry);
        } else if (!store.create(opened)) {
            throw new IllegalStateException("Decision package ID already exists");
        }
        return opened;
    }

    public Optional<DecisionPackage> find(UUID id, String viewer) {
        Entry entry = load(id);
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
            return save(entry);
        }
    }

    public DecisionPackage decideAll(UUID id, long expectedVersion, String approver, boolean approved) {
        return decideAll(id, expectedVersion, approver, approved, "");
    }

    public DecisionPackage decideAll(UUID id, long expectedVersion, String approver, boolean approved, String note) {
        Entry entry = requirePackage(id);
        synchronized (entry) {
            requireApprover(approver, entry);
            requirePending(entry, expectedVersion);
            Map<Integer, Verdict> votes = new LinkedHashMap<>();
            for (var item : entry.items) {
                votes.put(item.item().index(), new Verdict(approved, approver, note));
            }
            applyVotes(entry, votes);
            return save(entry);
        }
    }

    public DecisionPackage decideItems(UUID id, long expectedVersion, String approver, Map<Integer, Boolean> votes) {
        Map<Integer, Review> reviews = new LinkedHashMap<>();
        votes.forEach((index, approved) -> reviews.put(index, new Review(approved, "")));
        return reviewItems(id, expectedVersion, approver, reviews);
    }

    public record Review(boolean approved, String note) { }

    public DecisionPackage reviewItems(UUID id, long expectedVersion, String approver, Map<Integer, Review> reviews) {
        Entry entry = requirePackage(id);
        synchronized (entry) {
            requireApprover(approver, entry);
            requirePending(entry, expectedVersion);
            Map<Integer, Verdict> votes = new LinkedHashMap<>();
            Map.copyOf(reviews).forEach((index, review) ->
                    votes.put(index, new Verdict(review.approved(), approver, review.note())));
            applyVotes(entry, votes);
            return save(entry);
        }
    }

    public DecisionPackage cancel(UUID id, String requester) {
        Entry entry = requirePackage(id);
        synchronized (entry) {
            requireParticipant(requester, entry);
            expire(entry);
            cancel(entry);
            return save(entry);
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

    /** Starts a new review version so a host can revise approvals after a failed preview. */
    public DecisionPackage reopenReview(UUID id, long expectedVersion, String approver) {
        requireHostSettlement();
        Entry entry = requirePackage(id);
        requireApprover(approver, entry);
        expire(entry);
        requireVersion(entry, expectedVersion);
        if (entry.state != State.DECIDED) {
            throw new IllegalStateException("Only a decided package can reopen review");
        }
        entry.version++;
        entry.verdicts.clear();
        entry.state = State.PENDING;
        return save(entry);
    }

    public static final class CommitPlan {
        private final DecisionService owner;
        private final DecisionPackage snapshot;
        private final String requester;

        private CommitPlan(DecisionService owner, DecisionPackage snapshot, String requester) {
            this.owner = owner;
            this.snapshot = snapshot;
            this.requester = requester;
        }

        public DecisionPackage snapshot() {
            return snapshot;
        }

        public List<Item> calls() {
            return snapshot.receipt() == null ? snapshot.items().stream()
                    .filter(item -> snapshot.verdicts().get(item.index()).approved()).toList() : List.of();
        }

        public Optional<DecisionReceipt> receipt() {
            return Optional.ofNullable(snapshot.receipt());
        }
    }

    public record Completion(CommitPlan plan, Map<Integer, ToolResult> outcomes) {
        public Completion(CommitPlan plan) {
            this(plan, Map.of());
        }

        public Completion {
            Objects.requireNonNull(plan);
            outcomes = Map.copyOf(outcomes);
        }
    }

    /** Previews authorized commands without claiming or executing them. */
    public CommitPlan prepareCommit(UUID id, long expectedVersion, String requester) {
        requireHostSettlement();
        var entry = requirePackage(id);
        requireApprover(requester, entry);
        expire(entry);
        requireVersion(entry, expectedVersion);
        if (entry.state != State.DECIDED && entry.state != State.CLOSED) {
            throw new IllegalStateException("Cannot prepare a package in state " + entry.state);
        }
        var plan = new CommitPlan(this, entry.snapshot(), requester);
        checkCalls(plan);
        return plan;
    }

    public DecisionReceipt completeCommit(CommitPlan plan, String revisionRef) {
        return completeCommit(List.of(new Completion(plan)), revisionRef).getFirst();
    }

    /** Records receipts together, using the transaction or recovery contract of the host's store. */
    public synchronized List<DecisionReceipt> completeCommit(List<Completion> completions, String revisionRef) {
        requireHostSettlement();
        completions = List.copyOf(completions);
        if (completions.isEmpty() || Objects.requireNonNull(revisionRef).isBlank()) {
            throw new IllegalArgumentException("A settlement needs plans and a business revision reference");
        }
        var ids = new HashSet<UUID>();
        var updates = new ArrayList<DecisionStore.Update>();
        var receipts = new ArrayList<DecisionReceipt>();
        Instant completedAt = clock.instant();
        for (var completion : completions) {
            var plan = completion.plan();
            var snapshot = plan.snapshot();
            if (plan.owner != this || !ids.add(snapshot.id())) {
                throw new IllegalArgumentException("Plans must be distinct and prepared by this service");
            }
            var entry = requirePackage(snapshot.id());
            requireApprover(plan.requester, entry);
            expire(entry);
            requireVersion(entry, snapshot.version());
            if (entry.state == State.CLOSED && revisionRef.equals(entry.receipt.revisionRef())) {
                receipts.add(entry.receipt);
                continue;
            }
            if (!snapshot.equals(entry.snapshot()) || entry.state != State.DECIDED) {
                throw new IllegalStateException("Decision changed after the plan was prepared");
            }
            checkCalls(plan);
            Set<Integer> approved = new HashSet<>();
            plan.calls().forEach(item -> approved.add(item.index()));
            if (!completion.outcomes().isEmpty() && (!completion.outcomes().keySet().equals(approved)
                    || completion.outcomes().values().stream().anyMatch(outcome -> outcome.code() != OK))) {
                throw new IllegalArgumentException("Successful results must match the approved items");
            }
            Map<Integer, ToolResult> outcomes = new LinkedHashMap<>();
            for (var item : snapshot.items()) {
                int index = item.index();
                outcomes.put(index, approved.contains(index)
                        ? completion.outcomes().getOrDefault(index, ToolResult.ok("Applied by the host"))
                        : ToolResult.of(DENIED, "The item was not approved"));
            }
            var receipt = new DecisionReceipt(entry.id, entry.turnId, entry.version, entry.proposer,
                    plan.requester, completedAt, outcomes, revisionRef);
            entry.receipt = receipt;
            entry.state = State.CLOSED;
            updates.add(new DecisionStore.Update(entry.original, entry.snapshot()));
            receipts.add(receipt);
        }
        if (!updates.isEmpty() && (updates.size() != completions.size() || !store.compareAndSet(updates))) {
            throw new IllegalStateException("Settlement raced with another update or was only partially replayed");
        }
        return List.copyOf(receipts);
    }

    private void checkCalls(CommitPlan plan) {
        for (var item : plan.calls()) {
            if (!item.digest().equals(digest(item.arguments()))
                    || invoker.checkApproved(item.tool(), item.arguments(), plan.snapshot().caller()).code() != OK) {
                throw new SecurityException("An approved item no longer passes authorization: " + item.index());
            }
        }
    }

    private void requireHostSettlement() {
        if (store == null || closed) {
            throw new IllegalStateException("An open host-managed decision service is required");
        }
    }

    public CompletionStage<DecisionReceipt> commit(UUID id, String requester) {
        if (store != null) {
            throw new IllegalStateException("This agent uses host-managed settlement");
        }
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

    private void applyVotes(Entry entry, Map<Integer, Verdict> votes) {
        if (votes.isEmpty() || !entry.indices().containsAll(votes.keySet())) {
            throw new IllegalArgumentException("Verdicts must reference existing items");
        }
        votes.forEach((index, verdict) -> {
            Verdict existing = entry.verdicts.get(index);
            if (existing != null && (existing.approved() != verdict.approved()
                    || !existing.note().equals(verdict.note()))) {
                throw new IllegalStateException("Item " + index + " already has a different verdict");
            }
        });
        votes.forEach(entry.verdicts::putIfAbsent);
        if (entry.verdicts.size() == entry.items.size()) {
            entry.state = State.DECIDED;
        }
    }

    private Entry requirePackage(UUID id) {
        return Optional.ofNullable(load(id))
                .orElseThrow(() -> new IllegalArgumentException("Unknown decision package"));
    }

    private Entry load(UUID id) {
        return store == null ? packages.get(id) : store.find(id).map(Entry::new).orElse(null);
    }

    private DecisionPackage save(Entry entry) {
        DecisionPackage next = entry.snapshot();
        if (store != null && (closed || !store.compareAndSet(List.of(new DecisionStore.Update(entry.original, next))))) {
            throw new IllegalStateException("Decision package changed or the service closed");
        }
        return next;
    }

    private void requirePending(Entry entry, long expectedVersion) {
        expire(entry);
        requireVersion(entry, expectedVersion);
        if (entry.state != State.PENDING) {
            throw new IllegalStateException("Decision package is not pending at version " + expectedVersion);
        }
    }

    private static void requireVersion(Entry entry, long expectedVersion) {
        if (entry.version != expectedVersion) {
            throw new IllegalStateException("Decision package changed from version " + expectedVersion);
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
        private final InvocationContext caller;
        private final Instant expiresAt;
        private final DecisionPackage original;
        private final Map<Integer, Verdict> verdicts = new LinkedHashMap<>();
        private final CompletableFuture<DecisionReceipt> completion = new CompletableFuture<>();
        private List<PendingItem> items;
        private State state = State.PENDING;
        private long version = 1;
        private boolean cancelled;
        private String submittedBy;
        private DecisionReceipt receipt;

        private Entry(UUID id, InvocationContext caller, Instant expiresAt, List<PendingItem> items) {
            this.id = id;
            this.turnId = caller.requestId();
            this.proposer = caller.principal();
            this.caller = caller;
            this.expiresAt = expiresAt;
            this.items = items;
            this.original = null;
        }

        private Entry(DecisionPackage original) {
            this.id = original.id();
            this.turnId = original.turnId();
            this.proposer = original.proposer();
            this.caller = original.caller();
            this.expiresAt = original.expiresAt();
            this.items = original.items().stream().map(item -> new PendingItem(item, null)).toList();
            this.original = original;
            this.verdicts.putAll(original.verdicts());
            this.state = original.state();
            this.version = original.version();
            this.receipt = original.receipt();
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
                    items.stream().map(PendingItem::item).toList(), verdicts, receipt, caller);
        }
    }
}
