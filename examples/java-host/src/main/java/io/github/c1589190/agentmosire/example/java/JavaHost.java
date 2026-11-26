package io.github.c1589190.agentmosire.example.java;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import io.github.c1589190.agentmosire.example.catalog.CatalogTools;
import io.github.c1589190.agentmosire.example.labels.LabelTools;
import io.github.c1589190.agentmosire.runtime.AgentConfig;
import io.github.c1589190.agentmosire.runtime.AgentFactory;
import io.github.c1589190.agentmosire.tool.RolePolicy;

public final class JavaHost {
    private JavaHost() {
    }

    public static void main(String[] args) {
        var labels = new ConcurrentHashMap<String, String>();
        var tools = Set.of(CatalogTools.FIND, LabelTools.APPLY);
        var policy = new RolePolicy(caller -> Set.of("catalog-editor"), List.of(
                new RolePolicy.Grant("catalog-editor", tools, resource -> resource.type().equals("item"))));
        DemoModel scripted = prompt -> {
            long rounds = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
            return switch ((int) rounds) {
                case 0 -> requested(new ToolCall("lookup", "function", CatalogTools.FIND.externalName(),
                        "{\"item\":\"alpha\"}"),
                        new ToolCall("label-alpha", "function", LabelTools.APPLY.externalName(),
                                "{\"item\":\"alpha\",\"label\":\"reviewed\"}"));
                case 1 -> requested(new ToolCall("label-beta", "function", LabelTools.APPLY.externalName(),
                        "{\"item\":\"beta\",\"label\":\"reviewed\"}"));
                default -> new ChatResponse(List.of(new Generation(new AssistantMessage("Labels queued for approval."))));
            };
        };
        var config = AgentConfig.of("editor", tools, policy, (person, proposal) -> person.equals("reviewer"));
        try (var agent = new AgentFactory(scripted).create(config,
                List.of(CatalogTools.module(), LabelTools.module(labels)));
                var session = agent.openSession()) {
            var turn = session.chat("Review alpha and beta.");
            var id = turn.decisionPackages().getFirst();
            var pending = agent.decisions().find(id, "reviewer").orElseThrow();
            agent.decisions().decideItems(id, pending.version(), "reviewer", Map.of(0, true, 1, false));
            var receipt = agent.decisions().commit(id, "reviewer").toCompletableFuture().join();
            if (!labels.equals(Map.of("alpha", "reviewed")) || receipt.outcomes().size() != 2) {
                throw new IllegalStateException("Unexpected decision result");
            }
            System.out.println(turn.text());
            System.out.println("Recorded outcomes: " + receipt.outcomes());
        }
    }

    private static ChatResponse requested(ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(calls)).build())));
    }

    private interface DemoModel extends ChatModel {
        @Override
        default ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
