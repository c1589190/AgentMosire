// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.agentlib.agent.AgentConfig;
import io.github.c1589190.agentlib.approval.ApprovalCoordinator;
import io.github.c1589190.agentlib.approval.PendingApprovals;
import io.github.c1589190.agentlib.event.Event;
import io.github.c1589190.agentlib.event.EventBus;
import io.github.c1589190.agentlib.event.EventQuery;
import io.github.c1589190.agentlib.event.EventWrite;
import io.github.c1589190.agentlib.event.SqliteEventStore;
import io.github.c1589190.agentlib.llm.ContentPart;
import io.github.c1589190.agentlib.llm.LlmMessage;
import io.github.c1589190.agentlib.llm.LlmRequest;
import io.github.c1589190.agentlib.llm.LlmResponse;
import io.github.c1589190.agentlib.llm.ToolDef;
import io.github.c1589190.agentlib.permission.AccessToken;
import io.github.c1589190.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentlib.permission.ToolSpec;
import io.github.c1589190.agentlib.store.SqliteConversationStore;
import io.github.c1589190.agentlib.tool.AgentTool;
import io.github.c1589190.agentlib.tool.ToolRegistry;
import io.github.c1589190.agentlib.tool.ToolResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeEmbeddingTest {

  private static final AgentSpec SPEC = new AgentSpec(AgentConfig.builder("embedded").build());

  @TempDir Path directory;

  @Test
  void defaultMemoryRuntimeKeepsHistoryUntilResetAndIsolatesInstances() {
    List<LlmRequest> requests = new ArrayList<>();
    try (AgentRuntime runtime =
        AgentRuntime.builder(
                SPEC,
                request -> {
                  requests.add(request);
                  return LlmResponse.text("回复");
                })
            .build()) {
      assertThat(runtime.conversationId()).isEqualTo("embedded");
      assertThat(runtime.chat("第一轮").finalText()).contains("回复");
      assertThat(runtime.chat("第二轮").finalText()).contains("回复");
      assertThat(requests.get(1).messages())
          .containsSubsequence(LlmMessage.user("第一轮"), LlmMessage.user("第二轮"));

      runtime.resetSession("reset");
      assertThat(runtime.conversationId()).isEqualTo("reset");
      assertThat(runtime.chat("重置后").finalText()).contains("回复");
      assertThat(requests.get(2).messages())
          .contains(LlmMessage.user("重置后"))
          .doesNotContain(LlmMessage.user("第一轮"), LlmMessage.user("第二轮"));
      assertThat(
              runtime.queryEvents(
                  new EventQuery("embedded", EventTypes.CONVERSATION_TURN, "", -1, 10)))
          .extracting(Event::payload)
          .satisfiesExactly(
              event -> assertThat(event).contains("重置后"),
              event -> assertThat(event).contains("第二轮"),
              event -> assertThat(event).contains("第一轮"));
    }
    try (AgentRuntime fresh =
        AgentRuntime.builder(
                SPEC,
                request -> {
                  assertThat(request.messages()).doesNotContain(LlmMessage.user("重置后"));
                  return LlmResponse.text("独立实例");
                })
            .build()) {
      assertThat(fresh.conversationId()).isEqualTo("embedded");
      assertThat(fresh.chat("新实例").finalText()).contains("独立实例");
    }
  }

  @Test
  void toolConfigurationNarrowsHostGrantsForVisibilityAndExecution() {
    List<String> names = List.of("allowed", "config_denied", "host_denied", "outside_config");
    List<String> executions = new ArrayList<>();
    ToolRegistry registry = new ToolRegistry();
    for (String name : names) {
      registry.register(
          AgentTool.of(
              name,
              "测试工具",
              Map.of("type", "object"),
              ToolSpec.DEFAULT,
              context -> {
                executions.add(name);
                return ToolResult.ok("已执行");
              }));
    }
    AgentConfig config =
        AgentConfig.builder("embedded")
            .allowedTools(Set.of("allowed", "config_denied", "host_denied"))
            .deniedTools(Set.of("config_denied"))
            .build();
    AgentPermissionSet host =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().deny("host_denied").build();
    List<LlmRequest> requests = new ArrayList<>();
    List<ContentPart> calls =
        names.stream()
            .<ContentPart>map(name -> new ContentPart.ToolCall(name, name, Map.of()))
            .toList();
    try (AgentRuntime runtime =
        AgentRuntime.builder(
                new AgentSpec(config),
                request -> {
                  requests.add(request);
                  return requests.size() == 1
                      ? new LlmResponse(LlmMessage.assistant(calls), "fixture", 0, 0)
                      : LlmResponse.text("结束");
                })
            .toolRegistry(registry)
            .permissionSet(host)
            .build()) {
      assertThat(runtime.chat("检查工具权限").finalText()).contains("结束");
      assertThat(requests.getFirst().tools()).extracting(ToolDef::name).containsExactly("allowed");
      assertThat(executions).containsExactly("allowed");
      assertThat(
              requests.get(1).messages().stream()
                  .flatMap(message -> message.content().stream())
                  .filter(ContentPart.ToolResult.class::isInstance)
                  .map(ContentPart.ToolResult.class::cast))
          .extracting(ContentPart.ToolResult::isError)
          .containsExactly(false, true, true, true);
      assertThat(runtime.permissionSet().allowedTools())
          .containsExactlyInAnyOrder("allowed", "config_denied", "host_denied");
      assertThat(runtime.permissionSet().deniedTools())
          .containsExactlyInAnyOrder("config_denied", "host_denied");
      assertThat(
              runtime.queryEvents(
                  new EventQuery("embedded", EventTypes.PERMISSION_DENIED, "", -1, 10)))
          .extracting(Event::payload)
          .satisfiesExactly(
              event -> assertThat(event).contains("outside_config"),
              event -> assertThat(event).contains("host_denied"),
              event -> assertThat(event).contains("config_denied"));
    }
  }

  @Test
  void reopeningOwnedStorageResumesTheResetConversationAndPreservesTheOldHistory() {
    try (AgentRuntime first =
        AgentRuntime.builder(SPEC, request -> LlmResponse.text("fixture"))
            .dataDirectory(directory)
            .initialConversationId("first")
            .build()) {
      assertThat(first.conversationId()).isEqualTo("first");
      assertThat(first.chat("旧会话").finalText()).contains("fixture");
      first.resetSession("active");
      assertThat(first.chat("新会话").finalText()).contains("fixture");
    }
    try (AgentRuntime reopened =
        AgentRuntime.builder(
                SPEC,
                request -> {
                  assertThat(request.messages())
                      .containsSubsequence(LlmMessage.user("新会话"), LlmMessage.user("重启后续聊"))
                      .doesNotContain(LlmMessage.user("旧会话"));
                  return LlmResponse.text("continued");
                })
            .dataDirectory(directory)
            .initialConversationId("unused")
            .build()) {
      assertThat(reopened.conversationId()).isEqualTo("active");
      assertThat(reopened.chat("重启后续聊").finalText()).contains("continued");
    }
    try (SqliteConversationStore store =
        SqliteConversationStore.open(directory.resolve("events.db"))) {
      assertThat(store.currentConversationId()).contains("active");
      assertThat(store.load("first")).contains(LlmMessage.user("旧会话"));
      assertThat(store.load("active"))
          .containsSubsequence(LlmMessage.user("新会话"), LlmMessage.user("重启后续聊"));
    }
  }

  @Test
  void borrowedStorageRemainsUsableAndDrainsItsBusBeforeClosingTheDatabase() throws Exception {
    CountDownLatch listening = new CountDownLatch(1);
    CountDownLatch allowWrite = new CountDownLatch(1);
    try (RuntimeStorage storage = RuntimeStorage.open(directory, false);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      try (AgentRuntime runtime =
          AgentRuntime.builder(SPEC, request -> LlmResponse.text("fixture"))
              .storage(storage)
              .build()) {
        assertThat(runtime.chat("借用存储").finalText()).contains("fixture");
      }
      assertThat(storage.eventStore().count()).isPositive();

      ApprovalCoordinator approval =
          new ApprovalCoordinator(
              List.of(), List.of(), new PendingApprovals(), Duration.ZERO, storage.eventBus());
      assertThatThrownBy(
              () ->
                  AgentRuntime.builder(SPEC, request -> LlmResponse.text("unused"))
                      .storage(storage)
                      .approvalCoordinator(approval)
                      .build())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("审批")
          .hasMessageContaining("持久会话");

      storage
          .eventBus()
          .subscribe(
              event -> {
                listening.countDown();
                awaitWritePermission(allowWrite);
                storage.eventStore().append(EventWrite.of("test.mirrored", event.agent()));
              });
      Future<?> closing;
      try {
        storage
            .eventBus()
            .publish(storage.eventStore().append(EventWrite.of("test.source", "test")));
        assertThat(listening.await(5, TimeUnit.SECONDS)).isTrue();
        closing = workers.submit(storage::close);
        awaitBusRefusesSubscriptions(storage.eventBus());
      } finally {
        allowWrite.countDown();
      }
      closing.get(5, TimeUnit.SECONDS);
    }
    try (SqliteEventStore reopened = SqliteEventStore.open(directory.resolve("events.db"))) {
      assertThat(reopened.query(EventQuery.recent(20)))
          .extracting(Event::type)
          .contains("test.source", "test.mirrored");
    }
  }

  private static void awaitWritePermission(CountDownLatch permission) {
    try {
      assertThat(permission.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("等待镜像写入时中断", interrupted);
    }
  }

  private static void awaitBusRefusesSubscriptions(EventBus bus) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      try (var subscription = bus.subscribe(event -> {})) {
        // 回收拒绝订阅意味着已进入排空阶段。稍后重试，不提前放行镜像写入。
      } catch (IllegalStateException closed) {
        assertThat(closed).hasMessageContaining("EventBus 已关闭");
        return;
      }
      Thread.sleep(Duration.ofMillis(1));
    }
    throw new AssertionError("事件总线未进入关闭阶段");
  }
}
