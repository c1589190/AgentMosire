package io.github.c1589190.agentmosire.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;

import io.github.c1589190.agentmosire.runtime.AgentConfig;
import io.github.c1589190.agentmosire.runtime.AgentFactory;
import io.github.c1589190.agentmosire.runtime.TurnResult;
import io.github.c1589190.agentmosire.runtime.decision.DecisionPackage;
import io.github.c1589190.agentmosire.runtime.decision.DecisionService;
import io.github.c1589190.agentmosire.tool.JsonValues;

final class TerminalHarness {
    private static final String ASSISTANT = "terminal-agent";
    private static final String REVIEWER = "terminal-user";

    private TerminalHarness() {
    }

    static void run(ChatModel model, BufferedReader input, PrintWriter output) throws IOException {
        var config = new AgentConfig(ASSISTANT, Notes.TOOLS, Notes.policy(ASSISTANT),
                """
                You are the AgentMosire terminal assistant. Use the note tools to read or replace the session note.
                DECISION_QUEUED means the change is pending approval. Report it as pending.
                """,
                12, Duration.ofMinutes(2), Duration.ofMinutes(15), (person, pkg) -> REVIEWER.equals(person),
                event -> { });

        try (var agent = new AgentFactory(model).create(config, List.of(Notes.module()));
                var session = agent.openSession()) {
            while (true) {
                output.printf("你> ");
                String line = input.readLine();
                if (line == null || line.strip().equals("/exit")) {
                    return;
                }
                if (line.isBlank()) {
                    continue;
                }
                TurnResult turn = session.chat(line);
                if (!turn.text().isBlank()) {
                    output.println("助手> " + terminalText(turn.text()));
                }
                if (turn.status() != TurnResult.Status.COMPLETED) {
                    output.println("回合状态 " + turn.status());
                }
                for (UUID id : turn.decisionPackages()) {
                    if (!review(agent.decisions(), id, input, output)) {
                        return;
                    }
                }
            }
        }
    }

    private static boolean review(DecisionService decisions, UUID id, BufferedReader input, PrintWriter output)
            throws IOException {
        DecisionPackage pkg = decisions.find(id, REVIEWER).orElseThrow();
        if (pkg.state() != DecisionPackage.State.PENDING) {
            output.println("决策包状态 " + pkg.state());
            return true;
        }
        Map<Integer, Boolean> votes = new LinkedHashMap<>();
        for (var item : pkg.items()) {
            output.printf("批准 %s %s？[y/N] ", item.tool().externalName(),
                    terminalText(JsonValues.write(item.arguments())));
            String vote = input.readLine();
            if (vote == null) {
                decisions.cancel(id, REVIEWER);
                return false;
            }
            votes.put(item.index(), "y".equalsIgnoreCase(vote.strip()));
        }
        try {
            decisions.decideItems(id, pkg.version(), REVIEWER, votes);
            var receipt = decisions.commit(id, REVIEWER).toCompletableFuture().join();
            receipt.outcomes().forEach((index, result) ->
                    output.println("回执 " + result.code() + " · " + terminalText(result.message())));
        } catch (IllegalStateException expired) {
            var current = decisions.find(id, REVIEWER).orElseThrow();
            output.println("决策包状态 " + current.state());
        }
        return true;
    }

    private static String terminalText(String text) {
        return text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
    }
}
