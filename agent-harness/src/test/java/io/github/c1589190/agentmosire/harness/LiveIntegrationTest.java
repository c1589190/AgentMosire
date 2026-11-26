package io.github.c1589190.agentmosire.harness;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeTypeUtils;
import reactor.adapter.JdkFlowAdapter;

import io.github.c1589190.agentmosire.runtime.AgentConfig;
import io.github.c1589190.agentmosire.runtime.AgentEvent;
import io.github.c1589190.agentmosire.runtime.AgentFactory;
import io.github.c1589190.agentmosire.runtime.TurnResult;
import io.github.c1589190.agentmosire.runtime.decision.DecisionService;
import io.github.c1589190.agentmosire.runtime.decision.InMemoryDecisionStore;
import io.github.c1589190.agentmosire.search.DeepSeekWebClient;
import io.github.c1589190.agentmosire.search.SearchTools;
import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolRegistry;
import io.github.c1589190.agentmosire.tool.ToolResult;
import io.github.c1589190.agentmosire.workspace.WorkspaceTools;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "agentmosire.live", matches = "true")
class LiveIntegrationTest {
    @TempDir
    Path sandbox;

    @Test
    void realModelUsesVisualToolAndReturnsAHostManagedDecision() throws Exception {
        String base = setting("AGENTMOSIRE_LIVE_BASE_URL", "https://api.deepseek.com");
        String name = setting("AGENTMOSIRE_LIVE_MODEL", "deepseek-flash");
        String key = setting("AGENTMOSIRE_LIVE_API_KEY", System.getenv("DEEPSEEK_API_KEY"));
        assertTrue(key != null && !key.isBlank(), "Set a key for the live model");
        var model = Main.openAiModel(name, key, base);
        var reference = Files.createDirectory(sandbox.resolve("reference"));
        String code = visualCode(reference.resolve("card.png"));
        var workspace = new WorkspaceTools(List.of(new WorkspaceTools.Mount("reference", reference, false)));

        var proposal = new ToolId("settlement", "record");
        var schema = """
                {"type":"object","properties":{"code":{"type":"string","pattern":"^[A-Z0-9]{7}$"}},
                "required":["code"],"additionalProperties":false}
                """;
        var tool = new ToolDefinition(proposal, "Propose writing the exact seven-character code seen on the reference",
                schema, false, args -> List.of(), (caller, args) -> ToolDefinition.Verdict.ASK,
                (args, access) -> fail("The tool handler must never run for a host settlement"));
        var allowed = Set.of(WorkspaceTools.VIEW_IMAGE, proposal);
        var config = new AgentConfig("test-seat", allowed, policy(allowed),
                "Always use the required tools for this test. Tool images are attached to the next request.",
                12, Duration.ofSeconds(120), Duration.ofMinutes(10), (actor, pkg) -> actor.equals("gm"), ignored -> { });
        var store = new InMemoryDecisionStore();

        try (var agent = new AgentFactory(model).create(config,
                List.of(workspace.module(), new ToolModule("settlement", List.of(tool))), store);
                var vision = agent.openSession(); var session = agent.openSession()) {
            var uploaded = UserMessage.builder().text("Read the large seven-character code in the image. Reply only with the code.")
                    .media(new Media(MimeTypeUtils.IMAGE_PNG, new FileSystemResource(reference.resolve("card.png")))).build();
            var direct = vision.chat(uploaded);
            assertEquals(TurnResult.Status.COMPLETED, direct.status());
            assertTrue(direct.text().contains(code), "The model must read the uploaded image");

            var events = JdkFlowAdapter.flowPublisherToFlux(session.stream("""
                    Use workspace__view_image to read reference/card.png. Then use settlement__record with the
                    exact seven-character code shown in the image. Wait for the image before proposing.
                    After submitting, reply briefly. Do not guess the code.
                    """)).collectList().block(Duration.ofSeconds(125));
            var result = assertInstanceOf(AgentEvent.Ended.class, events.getLast()).result();
            assertEquals(TurnResult.Status.COMPLETED, result.status());
            assertEquals(1, result.decisionPackages().size());
            var service = agent.decisions();
            var id = result.decisionPackages().getFirst();
            var pending = service.find(id, "gm").orElseThrow();
            assertEquals(1, pending.items().size());
            assertEquals(code, pending.items().getFirst().arguments().get("code"));
            assertThrows(IllegalStateException.class, () -> service.prepareCommit(id, pending.version(), "gm"));

            service.reviewItems(id, pending.version(), "gm", Map.of(0, new DecisionService.Review(true, "Verified")));
            var plan = service.prepareCommit(id, pending.version(), "gm");
            assertEquals(code, plan.calls().getFirst().arguments().get("code"));
            assertNull(service.find(id, "gm").orElseThrow().receipt());
            var receipt = service.completeCommit(plan, "synthetic-world-node");
            assertEquals("synthetic-world-node", receipt.revisionRef());
            assertEquals(receipt, service.completeCommit(plan, "synthetic-world-node"));
        }
    }

    @Test
    void liveSearchAndFetchReturnUsableSources() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertTrue(key != null && !key.isBlank(), "Set DEEPSEEK_API_KEY for live Search/Fetch");
        try (var web = new DeepSeekWebClient(() -> key)) {
            var ids = Set.of(SearchTools.SEARCH, SearchTools.FETCH);
            var invoker = new ToolInvoker(new ToolRegistry(List.of(SearchTools.module(web, web))), policy(ids));
            var caller = InvocationContext.create("test-seat", "test");
            var search = invoker.invoke(SearchTools.SEARCH,
                    Map.of("query", "site:en.wikipedia.org Brittany France geography", "count", 5), caller);
            assertEquals(ToolResult.Code.OK, search.code(), search.message());
            assertFalse(assertInstanceOf(List.class, search.data().get("results")).isEmpty());
            var fetched = invoker.invoke(SearchTools.FETCH,
                    Map.of("url", "https://example.com/"), caller);
            assertEquals(ToolResult.Code.OK, fetched.code(), fetched.message());
            assertEquals("https://example.com/", fetched.data().get("url"));
            assertFalse(((String) fetched.data().get("content")).isBlank());

            var searched = new AtomicInteger();
            var model = Main.openAiModel(setting("AGENTMOSIRE_LIVE_MODEL", "deepseek-flash"),
                    setting("AGENTMOSIRE_LIVE_API_KEY", key),
                    setting("AGENTMOSIRE_LIVE_BASE_URL", "https://api.deepseek.com"));
            var config = AgentConfig.of("test-seat", Set.of(SearchTools.SEARCH), policy(ids), (actor, pkg) -> false);
            var searchModule = SearchTools.search(request -> {
                var response = web.search(request);
                if (!response.hits().isEmpty()) {
                    searched.incrementAndGet();
                }
                return response;
            });
            try (var agent = new AgentFactory(model).create(config, List.of(searchModule));
                    var session = agent.openSession()) {
                var result = session.chat("Call web__search for 'Brittany France geography' and give one source URL.");
                assertEquals(TurnResult.Status.COMPLETED, result.status());
                assertTrue(searched.get() > 0, "The model must successfully call the real Search tool");
            }
        }
    }

    private static String visualCode(Path image) throws Exception {
        String code = UUID.randomUUID().toString().substring(0, 7).toUpperCase(java.util.Locale.ROOT);
        var card = new BufferedImage(800, 320, BufferedImage.TYPE_INT_RGB);
        var graphics = card.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 800, 320);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 100));
            graphics.drawString(code, 100, 195);
        } finally {
            graphics.dispose();
        }
        assertTrue(ImageIO.write(card, "png", image.toFile()));
        return code;
    }

    private static RolePolicy policy(Set<ToolId> ids) {
        return new RolePolicy(caller -> Set.of("test"), List.of(new RolePolicy.Grant("test", ids,
                resource -> !resource.type().equals("workspace") || resource.id().equals("reference/card.png"))));
    }

    private static String setting(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
