package io.github.c1589190.agentmosire.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Flow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.session.advisor.SessionAdvisors;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage;
import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolInvoker.DeferredCall;
import io.github.c1589190.agentmosire.tool.springai.SpringTools;

import static io.github.c1589190.agentmosire.runtime.TurnResult.Status.*;

public final class AgentSession implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(AgentSession.class.getName());

    public record DraftItem(ToolId tool, Map<String, Object> arguments) { }

    private final Agent agent;
    private final UUID id;
    private Turn active;
    private boolean closed;

    AgentSession(Agent agent, UUID id) {
        this.agent = agent;
        this.id = id;
    }

    public UUID id() {
        return id;
    }

    public TurnResult chat(String message) {
        return run(message, false).ofType(AgentEvent.Ended.class).single().block().result();
    }

    public Flow.Publisher<AgentEvent> stream(String message) {
        return JdkFlowAdapter.publisherToFlowPublisher(run(message, true));
    }

    public List<DraftItem> previewDraft() {
        Turn turn = currentTurn();
        return turn == null ? List.of() : turn.preview();
    }

    public Optional<UUID> submitDraft() {
        Turn turn = currentTurn();
        if (turn == null) {
            return Optional.empty();
        }
        DecisionPackage opened = turn.sealWhileActive();
        if (opened != null) {
            agent.emit(ready(opened));
        }
        return Optional.ofNullable(opened).map(DecisionPackage::id);
    }

    public boolean cancel() {
        Turn turn = currentTurn();
        return turn != null && turn.requestStop(CANCELLED);
    }

    @Override
    public void close() {
        Turn turn;
        synchronized (this) {
            closed = true;
            turn = active;
        }
        if (turn == null) {
            agent.deleteSession(id);
        } else {
            turn.requestStop(CANCELLED);
        }
    }

    private synchronized Turn currentTurn() {
        return active;
    }

    private Flux<AgentEvent> run(String message, boolean streaming) {
        Objects.requireNonNull(message);
        return Flux.defer(() -> {
            Turn turn;
            synchronized (this) {
                if (closed || active != null) {
                    return Flux.just(new AgentEvent.Ended(id,
                            new TurnResult(UUID.randomUUID(), closed ? CLOSED : SESSION_BUSY, "", List.of())));
                }
                turn = new Turn();
                active = turn;
            }

            var started = new AgentEvent.Started(id, turn.id);
            agent.emit(started);
            Mono<TurnResult.Status> deadline = Mono.delay(agent.config.turnTimeout())
                    .flatMap(ignored -> {
                        turn.requestStop(TIMED_OUT);
                        return turn.stopSignal.asMono();
                    });
            Flux<AgentEvent> modelEvents = Flux.defer(() -> responses(turn, message, streaming))
                    .takeUntilOther(Mono.firstWithSignal(turn.stopSignal.asMono(), deadline))
                    .mapNotNull(response -> textEvent(turn, response))
                    .cast(AgentEvent.class)
                    .onErrorResume(failure -> {
                        LOGGER.log(System.Logger.Level.WARNING, "Agent turn {0} failed with {1}",
                                turn.id, failure.getClass().getSimpleName());
                        turn.recordStatus(FAILED);
                        return Flux.empty();
                    });

            return Flux.concat(Mono.just(started), modelEvents, Flux.defer(() -> complete(turn)))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            turn.requestStop(CANCELLED);
                            complete(turn);
                        }
                    });
        });
    }

    private Flux<ChatResponse> responses(Turn turn, String message, boolean streaming) {
        if (streaming) {
            return request(turn, message).stream().chatResponse();
        }
        return Mono.fromCallable(() -> {
            if (!turn.enterBlockingCall()) {
                return null;
            }
            try {
                return request(turn, message).call().chatResponse();
            } finally {
                turn.leaveBlockingCall();
                release(turn);
            }
        }).subscribeOn(Schedulers.boundedElastic()).flux();
    }

    private ChatClient.ChatClientRequestSpec request(Turn turn, String message) {
        var callbacks = agent.invoker.registry().tools().stream()
                .filter(tool -> agent.config.tools().contains(tool.id()))
                .filter(tool -> agent.invoker.isVisible(tool.id(), turn.caller))
                .map(tool -> SpringTools.callback(agent.invoker, tool.id(), turn.caller, turn::defer, turn::isRunning))
                .toList();
        return agent.client.prompt().user(message).tools(callbacks)
                .advisors(SessionAdvisors.session(id.toString()));
    }

    private AgentEvent.Text textEvent(Turn turn, ChatResponse response) {
        var generation = response.getResult();
        if (generation == null) {
            return null;
        }
        if (ToolCallLimitExceededException.FINISH_REASON.equals(generation.getMetadata().getFinishReason())) {
            turn.recordStatus(TOOL_LIMIT);
            return null;
        }
        String text = generation.getOutput().getText();
        if (text == null || text.isEmpty() || !turn.appendText(text)) {
            return null;
        }
        var event = new AgentEvent.Text(id, turn.id, text);
        agent.emit(event);
        return event;
    }

    private Flux<AgentEvent> complete(Turn turn) {
        Completion completion = turn.finish();
        if (completion == null) {
            release(turn);
            return Flux.empty();
        }
        release(turn);
        var ended = new AgentEvent.Ended(id, completion.result());
        if (completion.opened() == null) {
            agent.emit(ended);
            return Flux.just(ended);
        }
        var ready = ready(completion.opened());
        agent.emit(ready);
        agent.emit(ended);
        return Flux.just(ready, ended);
    }

    private synchronized void release(Turn turn) {
        if (active == turn && turn.canRelease()) {
            active = null;
            if (closed) {
                agent.deleteSession(id);
            }
        }
    }

    private AgentEvent.DecisionReady ready(DecisionPackage opened) {
        return new AgentEvent.DecisionReady(id, opened.turnId(), opened.id(), opened.items().size());
    }

    private record Completion(TurnResult result, DecisionPackage opened) { }

    private final class Turn {
        private final UUID id = UUID.randomUUID();
        private final InvocationContext caller = new InvocationContext(agent.config.principal(), agent.id().toString(), id);
        private final Sinks.One<TurnResult.Status> stopSignal = Sinks.one();
        private final List<DeferredCall> draft = new ArrayList<>();
        private final List<UUID> packages = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private TurnResult.Status status = COMPLETED;
        private boolean finished;
        private boolean blockingCall;

        synchronized boolean isRunning() {
            return !finished && status == COMPLETED;
        }

        synchronized boolean defer(DeferredCall call) {
            return isRunning() && draft.add(call);
        }

        synchronized List<DraftItem> preview() {
            return draft.stream().map(call -> new DraftItem(call.tool(), call.arguments())).toList();
        }

        synchronized DecisionPackage sealWhileActive() {
            return isRunning() ? seal() : null;
        }

        synchronized boolean appendText(String fragment) {
            if (!isRunning()) {
                return false;
            }
            text.append(fragment);
            return true;
        }

        synchronized void recordStatus(TurnResult.Status result) {
            if (isRunning()) {
                status = result;
            }
        }

        boolean requestStop(TurnResult.Status reason) {
            synchronized (this) {
                if (!isRunning()) {
                    return false;
                }
                status = reason;
            }
            stopSignal.tryEmitValue(reason);
            return true;
        }

        synchronized boolean enterBlockingCall() {
            if (!isRunning()) {
                return false;
            }
            blockingCall = true;
            return true;
        }

        synchronized void leaveBlockingCall() {
            blockingCall = false;
        }

        synchronized boolean canRelease() {
            return finished && !blockingCall;
        }

        synchronized Completion finish() {
            if (finished) {
                return null;
            }
            finished = true;
            DecisionPackage opened = null;
            if (status == COMPLETED) {
                try {
                    opened = seal();
                } catch (RuntimeException failure) {
                    status = FAILED;
                }
            }
            draft.clear();
            return new Completion(new TurnResult(id, status, text.toString(), packages), opened);
        }

        private DecisionPackage seal() {
            if (draft.isEmpty()) {
                return null;
            }
            DecisionPackage opened = agent.decisions().open(id, draft, agent.config.decisionLifetime());
            packages.add(opened.id());
            draft.clear();
            return opened;
        }
    }
}
