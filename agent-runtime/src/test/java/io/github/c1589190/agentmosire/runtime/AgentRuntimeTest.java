package io.github.c1589190.agentmosire.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage;
import io.github.c1589190.agentmosire.tool.AccessPolicy;
import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.Resource;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolResult;

import static io.github.c1589190.agentmosire.tool.ToolDefinition.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    private static final ToolId ASK_TOOL = new ToolId("labels", "apply");
    private static final ToolId READ = new ToolId("catalog", "find");
    private static final ToolId BLOCKED = new ToolId("catalog", "blocked");
    private static final String SCHEMA = """
            {"type":"object","properties":{"item":{"type":"string"}},"required":["item"]}
            """;

    @Test
    void groupsAcrossModelRoundsAndExecutesApprovedSubsetOnlyOnce() {
        var permitted = new AtomicReference<>(Set.of("alpha", "beta", "gamma"));
        var reads = new AtomicInteger();
        var writes = new AtomicInteger();
        var blockCount = new AtomicInteger();
        ToolModel model = prompt -> {
            List<ToolResponseMessage> results = results(prompt);
            return switch (results.size()) {
                case 0 -> requested(call("r", READ, "alpha"), call("a", ASK_TOOL, "alpha"),
                        call("n", BLOCKED, "alpha"));
                case 1 -> {
                    assertTrue(results.getLast().getResponses().stream()
                            .anyMatch(response -> response.responseData().contains("DECISION_QUEUED")));
                    yield requested(call("b", ASK_TOOL, "beta"), call("g", ASK_TOOL, "gamma"));
                }
                default -> answer("Review queued");
            };
        };
        try (Agent agent = create(model, permitted, reads, writes, blockCount, 25); var session = agent.openSession()) {
            TurnResult completed = session.chat("tag items");
            assertEquals(TurnResult.Status.COMPLETED, completed.status());
            assertEquals("Review queued", completed.text());
            assertEquals(1, reads.get());
            assertEquals(0, writes.get());
            assertEquals(0, blockCount.get());

            UUID id = completed.decisionPackages().getFirst();
            var service = agent.decisions();
            assertTrue(service.find(id, "stranger").isEmpty());
            var pkg = service.find(id, "reviewer").orElseThrow();
            assertEquals(3, pkg.items().size());
            assertEquals(DecisionPackage.State.PENDING,
                    service.decideItems(id, pkg.version(), "reviewer", Map.of(0, true)).state());
            service.decideItems(id, pkg.version(), "reviewer", Map.of(1, true, 2, false));
            permitted.set(Set.of("alpha", "gamma"));

            var first = service.commit(id, "reviewer");
            var duplicate = service.commit(id, "editor");
            var receipt = first.toCompletableFuture().join();
            assertEquals(receipt, duplicate.toCompletableFuture().join());
            assertEquals(ToolResult.Code.OK, receipt.outcomes().get(0).code());
            assertEquals(ToolResult.Code.DENIED, receipt.outcomes().get(1).code());
            assertEquals(ToolResult.Code.DENIED, receipt.outcomes().get(2).code());
            assertEquals(1, writes.get());
        }
    }

    @Test
    void streamPublishesDecisionBeforeTerminalEvent() {
        var writer = new AtomicInteger();
        ToolModel model = new ToolModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return results(prompt).isEmpty() ? requested(AgentRuntimeTest.call("a", ASK_TOOL, "alpha")) : answer("ready");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }
        };
        try (Agent agent = create(model, new AtomicReference<>(Set.of("alpha")), new AtomicInteger(), writer,
                new AtomicInteger(), 20); var session = agent.openSession()) {
            List<AgentEvent> events = JdkFlowAdapter.flowPublisherToFlux(session.stream("request"))
                    .collectList().block(Duration.ofSeconds(10));
            assertInstanceOf(AgentEvent.Started.class, events.getFirst());
            var ready = assertInstanceOf(AgentEvent.DecisionReady.class, events.get(events.size() - 2));
            var ended = assertInstanceOf(AgentEvent.Ended.class, events.getLast());
            assertEquals(TurnResult.Status.COMPLETED, ended.result().status());
            assertEquals("ready", ended.result().text());
            assertEquals(List.of(ready.packageId()), ended.result().decisionPackages());
            assertEquals(0, writer.get());
        }
    }

    @Test
    void instancesHaveIndependentToolSetsAndSessionHistory() {
        ToolModel model = prompt -> {
            var options = (ToolCallingChatOptions) prompt.getOptions();
            var names = options.getToolCallbacks().stream().map(callback -> callback.getToolDefinition().name()).toList();
            return answer(names + " history=" + prompt.getUserMessages().size());
        };
        var factory = new AgentFactory(model);
        var policy = policy(new AtomicReference<>(Set.of("alpha")));
        var modules = modules(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        try (var first = factory.create(AgentConfig.of("first", Set.of(READ, ASK_TOOL), policy, (p, d) -> false), modules);
                var second = factory.create(AgentConfig.of("second", Set.of(READ), policy, (p, d) -> false), modules);
                var left = first.openSession(); var right = second.openSession()) {
            var leftResult = CompletableFuture.supplyAsync(() -> left.chat("left"));
            var rightResult = CompletableFuture.supplyAsync(() -> right.chat("right"));
            assertTrue(leftResult.join().text().contains(ASK_TOOL.externalName()));
            assertEquals("[catalog__find] history=1", rightResult.join().text());
            first.close();
            assertEquals(TurnResult.Status.CLOSED, left.chat("closed").status());
            assertEquals("[catalog__find] history=2", right.chat("continue").text());
        }
    }

    @Test
    void runningCallCannotBeReplacedUntilBlockingProviderUnwinds() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        ToolModel blocking = prompt -> {
            started.countDown();
            release.join();
            return answer("done");
        };
        try (var agent = create(blocking, new AtomicReference<>(Set.of()), new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), 5); var session = agent.openSession()) {
            var first = CompletableFuture.supplyAsync(() -> session.chat("waiting"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(TurnResult.Status.SESSION_BUSY, session.chat("overlap").status());
            assertTrue(session.cancel());
            assertEquals(TurnResult.Status.CANCELLED, first.orTimeout(5, TimeUnit.SECONDS).join().status());
            assertEquals(TurnResult.Status.SESSION_BUSY, session.chat("too soon").status());
        } finally {
            release.complete(null);
        }
    }

    @Test
    void toolLimitEndsTurnWithoutSubmittingTheDraft() {
        ToolModel model = prompt -> requested(call("r" + results(prompt).size(), ASK_TOOL, "alpha"));
        var writer = new AtomicInteger();
        try (var agent = create(model, new AtomicReference<>(Set.of("alpha")), new AtomicInteger(), writer,
                new AtomicInteger(), 1); var session = agent.openSession()) {
            var result = session.chat("repeat");
            assertEquals(TurnResult.Status.TOOL_LIMIT, result.status());
            assertTrue(result.decisionPackages().isEmpty());
            assertEquals(0, writer.get());
        }
    }

    @Test
    void explicitlySealedDraftStaysSeparateFromLaterCalls() {
        var currentSession = new AtomicReference<AgentSession>();
        var firstPackage = new AtomicReference<UUID>();
        ToolModel model = prompt -> switch (results(prompt).size()) {
            case 0 -> requested(call("a", ASK_TOOL, "alpha"));
            case 1 -> {
                var session = currentSession.get();
                assertEquals(Map.of("item", "alpha"), session.previewDraft().getFirst().arguments());
                firstPackage.set(session.submitDraft().orElseThrow());
                yield requested(call("b", ASK_TOOL, "beta"));
            }
            default -> answer("Two packages");
        };
        try (var agent = create(model, new AtomicReference<>(Set.of("alpha", "beta")), new AtomicInteger(),
                new AtomicInteger(), new AtomicInteger(), 10); var session = agent.openSession()) {
            currentSession.set(session);
            var result = session.chat("Collect each item separately");
            assertEquals(TurnResult.Status.COMPLETED, result.status());
            assertEquals(2, result.decisionPackages().size());
            assertEquals(firstPackage.get(), result.decisionPackages().getFirst());
            var second = agent.decisions().find(result.decisionPackages().getLast(), "reviewer").orElseThrow();
            assertEquals(List.of(Map.of("item", "beta")),
                    second.items().stream().map(DecisionPackage.Item::arguments).toList());
        }
    }

    @Test
    void modelCannotCallARegisteredToolOutsideTheAgentToolSet() {
        var writer = new AtomicInteger();
        ToolModel model = prompt -> requested(call("outside", ASK_TOOL, "alpha"));
        var config = AgentConfig.of("editor", Set.of(READ), policy(new AtomicReference<>(Set.of("alpha"))),
                (principal, pkg) -> true);
        try (var agent = new AgentFactory(model).create(config,
                modules(new AtomicInteger(), writer, new AtomicInteger())); var session = agent.openSession()) {
            var result = session.chat("Try a tool outside the run configuration");
            assertEquals(TurnResult.Status.FAILED, result.status());
            assertTrue(result.decisionPackages().isEmpty());
            assertEquals(0, writer.get());
        }
    }

    private static List<ToolResponseMessage> results(Prompt prompt) {
        return prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).toList();
    }

    private static Agent create(ChatModel model, AtomicReference<Set<String>> permitted, AtomicInteger reads,
            AtomicInteger writes, AtomicInteger blocks, int budget) {
        var configuration = new AgentConfig("editor", Set.of(ASK_TOOL, READ, BLOCKED), policy(permitted), "", budget,
                Duration.ofSeconds(10), Duration.ofMinutes(1), (person, pkg) -> person.equals("reviewer"), event -> { });
        return new AgentFactory(model).create(configuration, modules(reads, writes, blocks));
    }

    private static List<ToolModule> modules(AtomicInteger reads, AtomicInteger writes, AtomicInteger blocks) {
        return List.of(new ToolModule("catalog", List.of(tool(READ, ALLOW, reads), tool(BLOCKED, BLOCK, blocks))),
                new ToolModule("labels", List.of(tool(ASK_TOOL, ASK, writes))));
    }

    private static ToolDefinition tool(ToolId id, ToolDefinition.Verdict verdict, AtomicInteger effects) {
        return new ToolDefinition(id, "Sample tool", SCHEMA, true,
                args -> List.of(new Resource("item", (String) args.get("item"), "use")), (caller, args) -> verdict,
                (args, access) -> ToolResult.ok("effects=" + effects.incrementAndGet()));
    }

    private static AccessPolicy policy(AtomicReference<Set<String>> permitted) {
        return new AccessPolicy() {
            @Override
            public boolean allowsTool(InvocationContext caller, ToolId tool) {
                return true;
            }

            @Override
            public boolean allowsResource(InvocationContext caller, ToolId tool, Resource resource) {
                return permitted.get().contains(resource.id());
            }
        };
    }

    private static ToolCall call(String id, ToolId tool, String item) {
        return new ToolCall(id, "function", tool.externalName(), "{\"item\":\"" + item + "\"}");
    }

    private static ChatResponse requested(ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(calls)).build())));
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private interface ToolModel extends ChatModel {
        @Override
        default ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
