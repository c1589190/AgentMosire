// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.brain.compact;

import io.github.c1589190.agentmosire.agentlib.llm.LlmMessage;
import io.github.c1589190.agentmosire.agentlib.llm.LlmResponse;
import java.util.List;
import java.util.Objects;

/**
 * 一次压缩检查的结果。
 *
 * <p>{@code turnInput} 保留仍待发送的内容。默认压缩器可直接写入纯文本 user 或续作提示，此时返回空列表。 待投递的图片及其同轮输入由 {@link
 * io.github.c1589190.agentmosire.brain.context.LlmRoundTrip} 在模型成功响应后写回。
 *
 * <p>本记录只携带结构性计数与摘要调用的记账对象，不携带摘要原文；摘要正文已经落在窗口 raw 的 compact 标记里。
 *
 * @param turnInput 本次检查后仍待发送的输入（非 null）
 * @param compacted 本次是否提交了压缩
 * @param reason 压缩原因：{@code "threshold"} / {@code "manual"}；未压缩为空串
 * @param beforeTokens 压缩前完整请求的估算 token
 * @param afterTokens 后校验请求的估算 token；未压缩时等于 beforeTokens
 * @param triggerTokens 本次触发线
 * @param droppedMessages 被投影屏蔽的可见消息条数
 * @param summaryTokens 摘要正文的估算 token
 * @param summarizerResponse 摘要调用的响应；未调用摘要时为 null
 * @param summarizerLatencyMs 摘要调用墙钟耗时毫秒（未调用为 0）
 */
public record CompactionOutcome(
    List<LlmMessage> turnInput,
    boolean compacted,
    String reason,
    int beforeTokens,
    int afterTokens,
    int triggerTokens,
    int droppedMessages,
    int summaryTokens,
    LlmResponse summarizerResponse,
    long summarizerLatencyMs) {

  public CompactionOutcome {
    turnInput = List.copyOf(Objects.requireNonNull(turnInput, "turnInput"));
    reason = reason == null ? "" : reason;
    if (summarizerLatencyMs < 0) {
      throw new IllegalArgumentException("summarizerLatencyMs 不得为负: " + summarizerLatencyMs);
    }
  }
}
