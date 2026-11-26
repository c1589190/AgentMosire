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

import org.junit.jupiter.api.Test;

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolRegistry;
import io.github.c1589190.agentmosire.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

class DecisionServiceTest {
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
