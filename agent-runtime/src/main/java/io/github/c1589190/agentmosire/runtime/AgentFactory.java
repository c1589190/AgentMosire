package io.github.c1589190.agentmosire.runtime;

import java.util.Collection;
import java.util.Objects;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallConfirmationPolicy;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;

import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolRegistry;
import io.github.c1589190.agentmosire.runtime.decision.DecisionStore;

public final class AgentFactory {
    private final ChatModel model;
    private final ObservationRegistry observations;
    private final SessionService sessions;

    public AgentFactory(ChatModel model) {
        this(model, ObservationRegistry.NOOP, DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build()).build());
    }

    public AgentFactory(ChatModel model, ObservationRegistry observations, SessionService sessions) {
        this.model = Objects.requireNonNull(model);
        this.observations = Objects.requireNonNull(observations);
        this.sessions = Objects.requireNonNull(sessions);
    }

    public Agent create(AgentConfig config, Collection<ToolModule> modules) {
        return create(config, new ToolRegistry(modules));
    }

    public Agent create(AgentConfig config, ToolRegistry registry) {
        return assemble(config, registry, null);
    }

    public Agent create(AgentConfig config, Collection<ToolModule> modules, DecisionStore decisionStore) {
        return create(config, new ToolRegistry(modules), decisionStore);
    }

    public Agent create(AgentConfig config, ToolRegistry registry, DecisionStore decisionStore) {
        return assemble(config, registry, Objects.requireNonNull(decisionStore));
    }

    private Agent assemble(AgentConfig config, ToolRegistry registry, DecisionStore decisionStore) {
        if (model.getOptions() instanceof ToolCallingChatOptions options) {
            if (options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty()) {
                throw new IllegalArgumentException("Register callbacks through the Agent tool registry, not model defaults");
            }
        } else if (!config.tools().isEmpty()) {
            throw new IllegalArgumentException("A model with ToolCallingChatOptions is required when tools are configured");
        }
        config.tools().forEach(id -> registry.find(id).orElseThrow(
                () -> new IllegalArgumentException("Agent references unregistered tool " + id)));
        var manager = ToolCallingManager.builder()
                .observationRegistry(observations)
                .maxTotalToolCalls(config.maxToolCalls())
                .resolutionFallbackEnabled(false)
                .toolCallConfirmationPolicy(ToolCallConfirmationPolicy.none())
                .build();
        var toolAdvisor = ToolCallingAdvisor.builder().toolCallingManager(manager);
        var builder = ChatClient.builder(model, observations, null, null, toolAdvisor)
                .defaultAdvisors(SessionAdvisors.builder(sessions).build())
                .defaultAdvisors(new ToolImageAdvisor());
        if (!config.instructions().isBlank()) {
            builder.defaultSystem(config.instructions());
        }
        return new Agent(config, builder.build(), sessions, new ToolInvoker(registry, config.policy()), decisionStore);
    }
}
