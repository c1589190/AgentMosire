package io.github.c1589190.agentmosire.runtime.decision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolRegistry;
import io.github.c1589190.agentmosire.tool.ToolResult;
import io.github.c1589190.agentmosire.tool.JsonValues;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class DecisionServiceTest {
    @Test
    void hostCanRetryPreviewAndAtomicallySettleMultiplePackagesWithoutExecutingHandlers() {
        var id = new ToolId("sample", "record");
        var allowed = new AtomicBoolean(true);
        var tool = new ToolDefinition(id, "Record sample result", "{\"type\":\"object\"}", false,
                args -> List.of(), (context, args) -> allowed.get() ? ToolDefinition.Verdict.ASK : ToolDefinition.Verdict.BLOCK,
                (args, access) -> fail("The host owns execution"));
        var policy = new RolePolicy(caller -> Set.of("sample"),
                List.of(new RolePolicy.Grant("sample", Set.of(id), resource -> true)));
        var registry = new ToolRegistry(List.of(new ToolModule("sample", List.of(tool))));
        var invoker = new ToolInvoker(registry, policy);
        var store = new InMemoryDecisionStore();
        DecisionPackage first;
        DecisionPackage second;
        try (var service = new DecisionService(invoker, (name, pkg) -> name.equals("gm"), store)) {
            first = open(service, invoker, id, "seat-a");
            second = open(service, invoker, id, "seat-b");
            service.reviewItems(first.id(), 1, "gm", Map.of(0, new DecisionService.Review(true, "supported"),
                    1, new DecisionService.Review(false, "skip")));
            service.decideAll(second.id(), 1, "gm", true);
        }
        try (var service = new DecisionService(new ToolInvoker(registry, policy), (name, pkg) -> name.equals("gm"),
                store)) {
            var snapshot = service.find(first.id(), "gm").orElseThrow();
            assertEquals("supported", snapshot.verdicts().get(0).note());
            assertEquals(snapshot, JsonMapper.builder().build().readValue(JsonValues.write(snapshot), DecisionPackage.class));
            assertThrows(SecurityException.class, () -> service.prepareCommit(first.id(), 1, "seat-a"));
            assertThrows(IllegalStateException.class, () -> service.commit(first.id(), "gm"));
            var a = service.prepareCommit(first.id(), 1, "gm");
            var b = service.prepareCommit(second.id(), 1, "gm");
            assertEquals(List.of(0), a.calls().stream().map(DecisionPackage.Item::index).toList());
            assertEquals(List.of(0, 1), b.calls().stream().map(DecisionPackage.Item::index).toList());

            var reopened = service.reopenReview(second.id(), 1, "gm");
            assertThrows(IllegalStateException.class,
                    () -> service.completeCommit(List.of(new DecisionService.Completion(a), new DecisionService.Completion(b)), "node"));
            assertNull(service.find(first.id(), "gm").orElseThrow().receipt());
            service.decideAll(second.id(), reopened.version(), "gm", true);
            var revised = service.prepareCommit(second.id(), reopened.version(), "gm");
            var batch = List.of(new DecisionService.Completion(a), new DecisionService.Completion(revised));
            allowed.set(false);
            assertThrows(SecurityException.class, () -> service.completeCommit(batch, "node"));
            assertNull(service.find(second.id(), "gm").orElseThrow().receipt());
            allowed.set(true);

            var receipts = service.completeCommit(batch, "node");
            assertEquals(receipts, service.completeCommit(batch, "node"));
            assertEquals("node", receipts.getFirst().revisionRef());
            assertEquals(ToolResult.Code.DENIED, receipts.getFirst().outcomes().get(1).code());
            assertThrows(IllegalStateException.class, () -> service.completeCommit(batch, "different-node"));
            assertEquals(receipts.getFirst(), service.prepareCommit(first.id(), 1, "gm").receipt().orElseThrow());
            assertTrue(service.prepareCommit(first.id(), 1, "gm").calls().isEmpty());
        }
    }

    private static DecisionPackage open(DecisionService service, ToolInvoker invoker, ToolId id, String seat) {
        var caller = InvocationContext.create(seat, "world");
        var pending = new ArrayList<ToolInvoker.DeferredCall>();
        invoker.invoke(id, Map.of("action", "first"), caller, pending::add);
        invoker.invoke(id, Map.of("action", "second"), caller, pending::add);
        return service.open(caller.requestId(), pending, Duration.ofMinutes(5));
    }

    @Test
    void outdatedVersionsAndExpiredPackagesCannotRun() {
        var id = new ToolId("sample", "record");
        var tool = new ToolDefinition(id, "Record sample result", "{\"type\":\"object\"}", false,
                args -> List.of(), (context, args) -> ToolDefinition.Verdict.ASK,
                (args, access) -> fail("No execution is permitted in this scenario"));
        var policy = new RolePolicy(caller -> Set.of("sample"), List.of(
                new RolePolicy.Grant("sample", Set.of(id), resource -> true)));
        var invoker = new ToolInvoker(new ToolRegistry(List.of(new ToolModule("sample", List.of(tool)))), policy);
        var now = new AtomicReference<>(Instant.EPOCH);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
            @Override public Instant instant() { return now.get(); }
        };
        var caller = InvocationContext.create("agent", "session");
        var pending = new ArrayList<ToolInvoker.DeferredCall>();
        assertEquals(ToolResult.Code.DECISION_QUEUED, invoker.invoke(id, Map.of(), caller, pending::add).code());
        invoker.invoke(id, Map.of(), caller, pending::add);
        try (var service = new DecisionService(invoker, (name, pkg) -> name.equals("supervisor"),
                clock, Runnable::run, receipt -> { })) {
            var opened = service.open(caller.requestId(), pending, Duration.ofSeconds(1));
            var edited = service.retainItems(opened.id(), opened.version(), "agent", Set.of(1));
            assertEquals(opened.version() + 1, edited.version());
            assertThrows(IllegalStateException.class,
                    () -> service.decideAll(edited.id(), opened.version(), "supervisor", true));
            assertThrows(SecurityException.class,
                    () -> service.decideAll(edited.id(), edited.version(), "agent", true));
            service.decideAll(edited.id(), edited.version(), "supervisor", true);
            now.set(Instant.EPOCH.plusSeconds(1));
            assertEquals(DecisionPackage.State.EXPIRED, service.find(edited.id(), "agent").orElseThrow().state());
            assertThrows(IllegalStateException.class, () -> service.commit(edited.id(), "supervisor"));
        }
    }
}
