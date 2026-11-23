// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.agent.AgentConfig;
import io.github.c1589190.agentmosire.agentlib.event.Event;
import io.github.c1589190.agentmosire.agentlib.event.EventBus;
import io.github.c1589190.agentmosire.agentlib.event.EventQuery;
import io.github.c1589190.agentmosire.agentlib.event.SqliteEventStore;
import io.github.c1589190.agentmosire.agentlib.llm.ContentPart;
import io.github.c1589190.agentmosire.agentlib.llm.FakeLlmClient;
import io.github.c1589190.agentmosire.agentlib.llm.LlmClient;
import io.github.c1589190.agentmosire.agentlib.llm.LlmResponse;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolExecutionGuard;
import io.github.c1589190.agentmosire.agentlib.tool.ToolRegistry;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.agentmosire.brain.context.BasicContextAssembler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code tool.call} 事件的<b>脱敏落点</b>在管线 emit 点（S4-C §一.3）：落账视图取自 {@link AgentTool#ledgerArgs}，
 * 而不是审批器、也不是事后擦除。
 *
 * <p>判别性：覆写了 {@code ledgerArgs} 的工具 ⇒ 事件里<b>找不到</b>参数明文；没覆写的工具（缺省实现）⇒ 事件里
 * <b>逐字</b>还是原参数（"既有工具行为逐字不变"不是一句口号）；未知工具名 ⇒ 退回原参数、且不把事件写崩。
 */
class AgentPipelineLedgerArgsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;

  @BeforeEach
  void setUp() {
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  @Test
  void ledgerArgsOverrideHidesThePlaintextFromTheEvent() {
    AgentRuntime runtime = runtime("vault", redactingTool("vault"));

    runtime.chat("存一个秘密");

    JsonNode args = toolCallArgs(0);
    assertThat(args.toString()).doesNotContain("TOPSECRET");
    assertThat(args.get("len").asInt()).isEqualTo("TOPSECRET".length());
    assertThat(args.get("class").asText()).isEqualTo("redacted");
  }

  @Test
  void defaultToolKeepsItsArgumentsVerbatim() {
    AgentRuntime runtime = runtime("echo", defaultTool("echo"));

    runtime.chat("回显");

    JsonNode args = toolCallArgs(0);
    assertThat(args.get("text").asText()).isEqualTo("TOPSECRET");
    assertThat(query(EventTypes.TOOL_RESULT).getFirst().payload())
        .contains("echo: TOPSECRET")
        .doesNotContain("resultChars");
  }

  @Test
  void optedInResultBecomesAnEventSummaryAndKeepsFullContentInTheConversation() throws Exception {
    String body = "只有模型收到的网页正文";
    AgentTool tool =
        new AgentTool() {
          @Override
          public String name() {
            return "fixture_fetch";
          }

          @Override
          public boolean summarizeLedgerResult() {
            return true;
          }

          @Override
          public ToolResult execute(ToolContext context) {
            return ToolResult.ok(body);
          }
        };
    AtomicInteger rounds = new AtomicInteger();
    LlmClient model =
        request -> {
          if (rounds.getAndIncrement() == 0) {
            return LlmResponse.toolCall("f-1", tool.name(), Map.of());
          }
          var results =
              request.messages().stream()
                  .flatMap(message -> message.content().stream())
                  .filter(ContentPart.ToolResult.class::isInstance)
                  .map(ContentPart.ToolResult.class::cast)
                  .toList();
          assertThat(results)
              .containsExactly(new ContentPart.ToolResult("f-1", tool.name(), body, null));
          return LlmResponse.text("已读");
        };

    assertThat(runtime(tool, model).chat("抓取示例").finalText()).contains("已读");
    assertThat(rounds.get()).isEqualTo(2);
    String payload = query(EventTypes.TOOL_RESULT).getFirst().payload();
    assertThat(payload).doesNotContain(body);
    JsonNode event = JSON.readTree(payload);
    assertThat(event.path("callId").asText()).isEqualTo("f-1");
    assertThat(event.path("ok").asBoolean()).isTrue();
    assertThat(event.path("resultChars").asInt()).isEqualTo(body.length());
    assertThat(event.path("durationMs").asLong(-1)).isNotNegative();
  }

  /** 未知工具名：退回原始 args（既有行为逐字不变），且结果是"工具不存在"的失败——别让事件把这段路径写崩。 */
  @Test
  void unknownToolFallsBackToRawArguments() {
    AgentRuntime runtime = runtime("nope", defaultTool("echo"));

    runtime.chat("调用不存在的工具");

    assertThat(toolCallArgs(0).get("text").asText()).isEqualTo("TOPSECRET");
    String result = query(EventTypes.TOOL_RESULT).get(0).payload();
    assertThat(result).contains("\"ok\":false").contains("工具不存在");
  }

  // ---------- 夹具 ----------

  private AgentRuntime runtime(String toolName, AgentTool tool) {
    return runtime(
        tool,
        FakeLlmClient.with(
            LlmResponse.toolCall("c-1", toolName, Map.of("text", "TOPSECRET")),
            LlmResponse.text("好了")));
  }

  private AgentRuntime runtime(AgentTool tool, LlmClient llm) {
    AgentConfig config = AgentConfig.builder("main").maxTurns(3).build();
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool);
    return new AgentRuntime(
        config,
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        store,
        bus,
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());
  }

  /** 覆写落账视图的工具：只报长度与一个哨兵 class，绝不回传参数原文。 */
  private static AgentTool redactingTool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Map<String, Object> ledgerArgs(ToolContext context) {
        String text = String.valueOf(context.arguments().get("text"));
        return Map.of("len", text.length(), "class", "redacted");
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("stored");
      }
    };
  }

  private static AgentTool defaultTool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("echo: " + context.arguments().get("text"));
      }
    };
  }

  private JsonNode toolCallArgs(int index) {
    List<Event> events = query(EventTypes.TOOL_CALL);
    assertThat(events).as("tool.call 事件条数").hasSizeGreaterThan(index);
    try {
      return JSON.readTree(events.get(index).payload()).get("args");
    } catch (Exception e) {
      throw new IllegalStateException(
          "tool.call payload 不是合法 JSON: " + events.get(index).payload(), e);
    }
  }

  private List<Event> query(String type) {
    return store.query(new EventQuery("", type, "", -1, 100));
  }
}
