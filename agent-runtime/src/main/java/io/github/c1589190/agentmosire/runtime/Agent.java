package io.github.c1589190.agentmosire.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;

import io.github.c1589190.agentmosire.runtime.decision.DecisionService;
import io.github.c1589190.agentmosire.tool.ToolInvoker;

public final class Agent implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(Agent.class.getName());

    private final UUID id = UUID.randomUUID();
    final AgentConfig config;
    final ChatClient client;
    final SessionService storage;
    final ToolInvoker invoker;
    private final DecisionService decisions;
    private final Map<UUID, AgentSession> sessions = new ConcurrentHashMap<>();
    private boolean closed;

    Agent(AgentConfig config, ChatClient client, SessionService storage, ToolInvoker invoker) {
        this.config = config;
        this.client = client;
        this.storage = storage;
        this.invoker = invoker;
        decisions = new DecisionService(invoker, config.approvers(), receipt -> {
            var codes = receipt.outcomes().entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey, entry -> entry.getValue().code()));
            emit(new AgentEvent.DecisionCommitted(id, receipt.packageId(), receipt.turnId(), receipt.version(),
                    receipt.executionPrincipal(), receipt.submittedBy(), receipt.completedAt(), codes));
        });
    }

    public UUID id() {
        return id;
    }

    public DecisionService decisions() {
        return decisions;
    }

    public synchronized AgentSession openSession() {
        if (closed) {
            throw new IllegalStateException("Agent is closed");
        }
        UUID sessionId = UUID.randomUUID();
        storage.create(CreateSessionRequest.builder()
                .id(sessionId.toString())
                .metadata(SessionAttributes.PRINCIPAL_NAME, config.principal())
                .build());
        AgentSession session = new AgentSession(this, sessionId);
        sessions.put(session.id(), session);
        return session;
    }

    void deleteSession(UUID sessionId) {
        if (sessions.remove(sessionId) != null) {
            storage.delete(sessionId.toString());
        }
    }

    void emit(AgentEvent event) {
        try {
            config.events().accept(event);
        } catch (RuntimeException listenerFailure) {
            LOGGER.log(System.Logger.Level.WARNING, "Agent event listener failed for {0}", event.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        List<AgentSession> owned;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            owned = List.copyOf(sessions.values());
        }
        owned.forEach(AgentSession::close);
        decisions.close();
    }
}
