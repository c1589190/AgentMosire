// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.agentlib.agent.AgentConfig;
import io.github.c1589190.agentlib.llm.ContentPart;
import io.github.c1589190.agentlib.llm.LlmClient;
import io.github.c1589190.agentlib.llm.LlmException;
import io.github.c1589190.agentlib.llm.LlmMessage;
import io.github.c1589190.agentlib.llm.LlmRequest;
import io.github.c1589190.agentlib.llm.LlmResponse;
import io.github.c1589190.agentlib.llm.VisionException;
import io.github.c1589190.agentlib.permission.AccessToken;
import io.github.c1589190.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentlib.permission.ToolSpec;
import io.github.c1589190.agentlib.store.SqliteConversationStore;
import io.github.c1589190.agentlib.tool.AgentTool;
import io.github.c1589190.agentlib.tool.ToolRegistry;
import io.github.c1589190.agentlib.tool.ToolResult;
import io.github.c1589190.brain.compact.CompactionPolicy;
import io.github.c1589190.brain.compact.DefaultContextCompactor;
import io.github.c1589190.brain.context.ContextWindow;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeVisionTest {

  private static final ContentPart.Image FIRST = new ContentPart.Image("image/png", "first");
  private static final ContentPart.Image SECOND = new ContentPart.Image("image/gif", "second");
  private static final ContentPart.Image THIRD = new ContentPart.Image("image/webp", "third");
  private static final AgentSpec SPEC =
      new AgentSpec(
          AgentConfig.builder("vision-example").allowedTools(Set.of("capture", "render")).build());
  private static final AgentPermissionSet PERMISSIONS =
      AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();

  @TempDir Path dataDir;

  @Test
  void chatPartsAcceptsAnImageAloneAndOrderedImagesWithText() {
    RecordingModel model =
        new RecordingModel(true, LlmResponse.text("第一轮"), LlmResponse.text("第二轮"));
    List<ContentPart> followUp =
        List.of(new ContentPart.Text("对比"), SECOND, new ContentPart.Text("和"), THIRD);
    try (AgentRuntime runtime = AgentRuntime.builder(SPEC, model).build()) {
      assertThat(runtime.supportsVision()).isTrue();
      assertThat(runtime.chatParts(List.of(FIRST)).finalText()).contains("第一轮");
      assertThat(runtime.chatParts(followUp).finalText()).contains("第二轮");
    }

    LlmMessage first = new LlmMessage(LlmMessage.ROLE_USER, List.of(FIRST));
    assertThat(model.requests).hasSize(2);
    assertThat(model.requests.getFirst().messages()).last().isEqualTo(first);
    assertThat(model.requests.getLast().messages())
        .containsSubsequence(first, new LlmMessage(LlmMessage.ROLE_USER, followUp));
  }

  @Test
  void repliesForTheWholeToolBatchPrecedeItsOrderedUserImages() {
    RecordingModel model = new RecordingModel(true, severalToolCalls(), LlmResponse.text("已查看"));
    ToolRegistry tools =
        tools(
            () -> ToolResult.okImages("两张", List.of(FIRST, SECOND)),
            () -> ToolResult.okImage("一张", THIRD));
    try (AgentRuntime runtime =
        AgentRuntime.builder(SPEC, model).toolRegistry(tools).permissionSet(PERMISSIONS).build()) {
      assertThat(runtime.chat("调用两个工具").finalText()).contains("已查看");
    }

    assertThat(model.requests).hasSize(2);
    List<LlmMessage> messages = model.requests.getLast().messages();
    assertThat(messages)
        .extracting(LlmMessage::role)
        .containsExactly("system", "user", "assistant", "tool", "tool", "user", "user");
    assertThat(toolReplies(messages))
        .containsExactly(
            new ContentPart.ToolResult("call-a", "capture", "两张", null),
            new ContentPart.ToolResult("call-b", "render", "一张", null));
    assertThat(images(messages.get(messages.size() - 2))).containsExactly(FIRST, SECOND);
    assertThat(images(messages.getLast())).containsExactly(THIRD);
  }

  @Test
  void textRouteRejectsUserAndToolImagesAndKeepsPairedToolRepliesForReopen() {
    RecordingModel model = new RecordingModel(false, severalToolCalls());
    ToolRegistry tools = tools(() -> ToolResult.okImage("未发送", FIRST), () -> ToolResult.ok("普通文字"));
    try (AgentRuntime runtime =
        AgentRuntime.builder(SPEC, model)
            .dataDirectory(dataDir)
            .toolRegistry(tools)
            .permissionSet(PERMISSIONS)
            .build()) {
      assertThatThrownBy(() -> runtime.chatParts(List.of(FIRST)))
          .isInstanceOfSatisfying(
              VisionException.class,
              error -> assertThat(error.code()).isEqualTo(VisionException.VISION_UNSUPPORTED));
      assertThat(model.requests).isEmpty();

      assertThatThrownBy(() -> runtime.chat("调用两个工具"))
          .isInstanceOfSatisfying(
              VisionException.class,
              error -> assertThat(error.code()).isEqualTo(VisionException.VISION_UNSUPPORTED));
      assertThat(model.requests).hasSize(1);
    }

    RecordingModel reopened = new RecordingModel(false, LlmResponse.text("正常续聊"));
    try (AgentRuntime runtime =
        AgentRuntime.builder(SPEC, reopened).dataDirectory(dataDir).build()) {
      assertThat(runtime.chat("现在只用文字").finalText()).contains("正常续聊");
    }
    assertThat(reopened.requests).hasSize(1);
    List<LlmMessage> messages = reopened.requests.getFirst().messages();
    assertThat(messages).allSatisfy(message -> assertThat(images(message)).isEmpty());
    assertThat(toolReplies(messages))
        .satisfiesExactly(
            reply -> {
              assertThat(reply.toolCallId()).isEqualTo("call-a");
              assertThat(reply.name()).isEqualTo("capture");
              assertThat(reply.error()).contains(VisionException.VISION_UNSUPPORTED);
            },
            reply ->
                assertThat(reply)
                    .isEqualTo(new ContentPart.ToolResult("call-b", "render", "普通文字", null)));
  }

  @Test
  void forcedCompactionSummarizesTheOldAnswerInTextAndDeliversOnlyPendingImages() {
    String conversation = "persisted-vision";
    LlmMessage oldInput = LlmMessage.user("旧图里的形状是什么？", List.of(FIRST));
    LlmResponse oldAnswer = LlmResponse.text("旧图里面是蓝色三角形");
    RecordingModel original = new RecordingModel(true, oldAnswer);
    try (AgentRuntime runtime =
        AgentRuntime.builder(SPEC, original).dataDirectory(dataDir).build()) {
      runtime.resetSession(conversation);
      assertThat(runtime.chatParts(oldInput.content()).finalText()).contains("旧图里面是蓝色三角形");
    }
    assertThat(imagesIn(original.requests.getFirst().messages())).containsExactly(FIRST);

    String checkpointText = "已辨认蓝色三角形";
    RecordingModel summary = new RecordingModel(false, LlmResponse.text(checkpointText));
    DefaultContextCompactor compactor =
        new DefaultContextCompactor(summary, new CompactionPolicy(12000, 512, 256, 0, "摘要"));
    List<ContentPart> pendingParts =
        List.of(new ContentPart.Text("看左图"), SECOND, new ContentPart.Text("再看右图"), THIRD);
    LlmMessage pending = new LlmMessage(LlmMessage.ROLE_USER, pendingParts);
    RecordingModel after =
        new RecordingModel(true, LlmResponse.text("已经对比"), LlmResponse.text("继续解释"));
    try (AgentRuntime runtime =
        AgentRuntime.builder(SPEC, after)
            .dataDirectory(dataDir)
            .contextCompactor(compactor)
            .build()) {
      assertThat(runtime.conversationId()).isEqualTo(conversation);
      runtime.requestCompaction();
      assertThat(runtime.chatParts(pendingParts).finalText()).contains("已经对比");
      assertThat(runtime.chat("同进程继续").finalText()).contains("继续解释");
    }

    assertThat(summary.requests).hasSize(1);
    List<LlmMessage> summarized = summary.requests.getFirst().messages();
    assertThat(imagesIn(summarized)).isEmpty();
    assertThat(summarized)
        .containsSubsequence(LlmMessage.user("旧图里的形状是什么？"), oldAnswer.assistantMessage());
    assertThat(after.requests).hasSize(2);
    List<LlmMessage> firstCompactedRequest = after.requests.getFirst().messages();
    assertThat(imagesIn(firstCompactedRequest)).containsExactly(SECOND, THIRD);
    assertThat(firstCompactedRequest).endsWith(checkpoint(checkpointText), pending);
    assertThat(imagesIn(after.requests.getLast().messages())).isEmpty();

    RecordingModel reopened = new RecordingModel(false, LlmResponse.text("重启后续聊"));
    try (AgentRuntime runtime =
        AgentRuntime.builder(SPEC, reopened).dataDirectory(dataDir).build()) {
      assertThat(runtime.conversationId()).isEqualTo(conversation);
      assertThat(runtime.chat("只处理文字").finalText()).contains("重启后续聊");
    }
    assertThat(reopened.requests).hasSize(1);
    List<LlmMessage> reopenedMessages = reopened.requests.getFirst().messages();
    assertThat(imagesIn(reopenedMessages)).isEmpty();
    assertThat(reopenedMessages)
        .contains(
            LlmMessage.user(
                ContextWindow.COMPACTION_CHECKPOINT_PREFIX
                    + checkpointText
                    + ContextWindow.COMPACTION_CHECKPOINT_SUFFIX
                    + "\n\n看左图\n再看右图"));

    try (SqliteConversationStore store =
        SqliteConversationStore.open(dataDir.resolve("events.db"))) {
      LlmMessage marker =
          new LlmMessage(ContextWindow.ROLE_COMPACT, List.of(new ContentPart.Text(checkpointText)));
      List<LlmMessage> raw = store.load(conversation);
      assertThat(raw).containsSubsequence(oldInput, oldAnswer.assistantMessage(), marker, pending);
      assertThat(imagesIn(raw)).containsExactly(FIRST, SECOND, THIRD);
    }
  }

  @Test
  void forcedCompactionBetweenToolAndModelKeepsTheNewImagesUntilTheirFirstModelRequest() {
    RecordingModel summary = new RecordingModel(false, LlmResponse.text("压缩前的操作"));
    DefaultContextCompactor compactor =
        new DefaultContextCompactor(summary, new CompactionPolicy(12000, 512, 256, 0, "摘要"));
    RecordingModel model =
        new RecordingModel(
            true,
            LlmResponse.toolCall("call-a", "capture", Map.of()),
            LlmResponse.text("已查看"),
            LlmResponse.text("文字续聊"));
    ToolRegistry tools = new ToolRegistry();
    tools.register(
        tool(
            "capture",
            () -> {
              compactor.requestCompaction();
              return ToolResult.okImages("两张", List.of(FIRST, SECOND));
            }));
    try (AgentRuntime runtime =
        AgentRuntime.builder(SPEC, model)
            .toolRegistry(tools)
            .permissionSet(PERMISSIONS)
            .contextCompactor(compactor)
            .build()) {
      assertThat(runtime.chat("看工具给的图").finalText()).contains("已查看");
      assertThat(runtime.chat("继续聊文字").finalText()).contains("文字续聊");
    }

    assertThat(summary.requests).hasSize(1);
    List<LlmMessage> summarizerMessages = summary.requests.getFirst().messages();
    assertThat(imagesIn(summarizerMessages)).isEmpty();
    assertThat(toolReplies(summarizerMessages))
        .containsExactly(new ContentPart.ToolResult("call-a", "capture", "两张", null));
    assertThat(model.requests).hasSize(3);
    List<LlmMessage> imageRequest = model.requests.get(1).messages();
    assertThat(imageRequest)
        .endsWith(
            checkpoint("压缩前的操作"), LlmMessage.user("工具结果图片，调用 ID 为 call-a", List.of(FIRST, SECOND)));
    assertThat(imagesIn(imageRequest)).containsExactly(FIRST, SECOND);
    assertThat(imagesIn(model.requests.getLast().messages())).isEmpty();
  }

  @Test
  void failedImageRequestIsRecordedLocallyWithoutResendingItOnTheNextTurn() {
    String conversation = "failed-image";
    List<LlmRequest> requests = new ArrayList<>();
    LlmClient model =
        new LlmClient() {
          @Override
          public boolean supportsVision() {
            return true;
          }

          @Override
          public LlmResponse chat(LlmRequest request) {
            requests.add(request);
            return switch (requests.size()) {
              case 1 -> LlmResponse.text("已经收到前文");
              case 2 -> throw new LlmException("fixture unavailable", LlmException.Kind.TRANSPORT);
              case 3 -> LlmResponse.text("继续");
              default -> throw new AssertionError("unexpected model request");
            };
          }
        };

    ContentPart.Text failedText = new ContentPart.Text("解释这张图");
    try (AgentRuntime runtime =
        AgentRuntime.builder(SPEC, model)
            .dataDirectory(dataDir)
            .initialConversationId(conversation)
            .build()) {
      assertThat(runtime.chat("记录之前的文字").finalText()).contains("已经收到前文");
      assertThat(runtime.chatParts(List.of(failedText, FIRST)).stopReason())
          .isEqualTo(StopReason.LLM_ERROR);
      assertThat(runtime.chat("先继续文字").finalText()).contains("继续");
    }
    assertThat(requests).hasSize(3);
    assertThat(imagesIn(requests.get(1).messages())).containsExactly(FIRST);
    List<LlmMessage> followUp = requests.getLast().messages();
    assertThat(imagesIn(followUp)).isEmpty();
    assertThat(followUp)
        .containsSubsequence(
            LlmMessage.user("记录之前的文字"),
            LlmResponse.text("已经收到前文").assistantMessage(),
            LlmMessage.user("先继续文字"));
    assertThat(followUp)
        .anySatisfy(
            message -> {
              assertThat(message.role()).isEqualTo(LlmMessage.ROLE_USER);
              assertThat(message.content()).contains(failedText);
            });

    try (SqliteConversationStore store =
        SqliteConversationStore.open(dataDir.resolve("events.db"))) {
      assertThat(store.load(conversation))
          .filteredOn(message -> !images(message).isEmpty())
          .singleElement()
          .satisfies(
              message -> {
                assertThat(message.role()).isNotEqualTo(LlmMessage.ROLE_USER);
                assertThat(message.content()).containsExactly(failedText, FIRST);
              });
    }
  }

  @Test
  void cancelDuringImagePreflightStopsBeforeTheFirstModelRequest() throws Exception {
    CountDownLatch validating = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger requests = new AtomicInteger();
    LlmClient model =
        new LlmClient() {
          @Override
          public boolean supportsVision() {
            return true;
          }

          @Override
          public void validateImages(List<ContentPart.Image> images) {
            assertThat(images).containsExactly(FIRST);
            validating.countDown();
            try {
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
          }

          @Override
          public LlmResponse chat(LlmRequest request) {
            requests.incrementAndGet();
            return LlmResponse.text("unexpected");
          }
        };

    try (AgentRuntime runtime = AgentRuntime.builder(SPEC, model).build();
        var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      var turn = worker.submit(() -> runtime.chatParts(List.of(FIRST)));
      try {
        assertThat(validating.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.cancel();
      } finally {
        release.countDown();
      }
      assertThat(turn.get(5, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.CANCELLED);
    }
    assertThat(requests).hasValue(0);
  }

  @Test
  void alreadyCancelledImageSubmissionSkipsTheModelAndDoesNotAffectTheNextTurn() {
    RecordingModel model = new RecordingModel(true, LlmResponse.text("文字续聊"));
    try (AgentRuntime runtime = AgentRuntime.builder(SPEC, model).build()) {
      assertThat(runtime.chatParts(List.of(FIRST), () -> true).stopReason())
          .isEqualTo(StopReason.CANCELLED);
      assertThat(model.requests).isEmpty();
      assertThat(runtime.chat("下一轮").finalText()).contains("文字续聊");
    }

    assertThat(model.requests).hasSize(1);
    assertThat(model.requests.getFirst().messages())
        .filteredOn(message -> LlmMessage.ROLE_USER.equals(message.role()))
        .containsExactly(LlmMessage.user("下一轮"));
  }

  private static LlmResponse severalToolCalls() {
    return new LlmResponse(
        LlmMessage.assistant(
            List.of(
                new ContentPart.ToolCall("call-a", "capture", Map.of()),
                new ContentPart.ToolCall("call-b", "render", Map.of()))),
        "fixture",
        0,
        0);
  }

  private static ToolRegistry tools(Supplier<ToolResult> capture, Supplier<ToolResult> render) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(tool("capture", capture));
    tools.register(tool("render", render));
    return tools;
  }

  private static AgentTool tool(String name, Supplier<ToolResult> result) {
    return AgentTool.of(
        name, "生成视图", Map.of("type", "object"), ToolSpec.DEFAULT, c -> result.get());
  }

  private static List<ContentPart.Image> images(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Image.class::isInstance)
        .map(ContentPart.Image.class::cast)
        .toList();
  }

  private static List<ContentPart.Image> imagesIn(List<LlmMessage> messages) {
    return messages.stream().flatMap(message -> images(message).stream()).toList();
  }

  private static LlmMessage checkpoint(String summary) {
    return LlmMessage.user(
        ContextWindow.COMPACTION_CHECKPOINT_PREFIX
            + summary
            + ContextWindow.COMPACTION_CHECKPOINT_SUFFIX);
  }

  private static List<ContentPart.ToolResult> toolReplies(List<LlmMessage> messages) {
    return messages.stream()
        .flatMap(message -> message.content().stream())
        .filter(ContentPart.ToolResult.class::isInstance)
        .map(ContentPart.ToolResult.class::cast)
        .toList();
  }

  private static final class RecordingModel implements LlmClient {
    final List<LlmRequest> requests = new ArrayList<>();
    private final boolean vision;
    private final List<LlmResponse> script;

    RecordingModel(boolean vision, LlmResponse... script) {
      this.vision = vision;
      this.script = List.of(script);
    }

    @Override
    public boolean supportsVision() {
      return vision;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      int index = requests.size();
      requests.add(request);
      return script.get(index);
    }
  }
}
