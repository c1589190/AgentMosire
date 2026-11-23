// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentlib.tool;

import io.github.c1589190.agentlib.llm.ContentPart;
import io.github.c1589190.agentlib.llm.ToolAssetResolver;
import java.util.List;
import java.util.Objects;

/**
 * 工具执行结果。
 *
 * <p>约定：{@code code == null} 为成功（消息前置于 LLM 的 "tool output"）；{@code code != null} 为失败（{@code code}
 * 为稳定错误标识，如 {@code PERMISSION_DENIED}/{@code TOOL_NOT_FOUND}）。 {@code assetDocIds}
 * 为资产引用：超出上下文预算的大结果（文档/日志）暂存为 docId，LLM 拿到的是 引用而非全文（计划报告 7.4 "结果溢出暂存 docId"）。
 *
 * <p>原生图片用有序的 {@code images} 携带媒体类型与资产引用，宿主通过 {@link ToolAssetResolver} 提供字节。 文档和日志继续用 {@code
 * assetDocIds}；结果对象可以进入事件或会话，不携带图片字节。
 */
public record ToolResult(
    String code, String message, List<String> assetDocIds, List<ContentPart.Image> images) {

  public ToolResult {
    Objects.requireNonNull(message, "message");
    assetDocIds = assetDocIds == null ? List.of() : List.copyOf(assetDocIds);
    images = images == null ? List.of() : List.copyOf(images);
    if (code != null && !images.isEmpty()) {
      throw new IllegalArgumentException("失败结果不能包含图片");
    }
  }

  /** 兼容原有文字与文档引用结果。 */
  public ToolResult(String code, String message, List<String> assetDocIds) {
    this(code, message, assetDocIds, List.of());
  }

  public static ToolResult ok(String message) {
    return new ToolResult(null, message, List.of());
  }

  /**
   * 成功 + 文档或工件引用。旧 MCP 客户端仍可获取这些引用指向的图片工件。
   *
   * @param message 回灌给模型的文本（永远在；图片是<b>附加</b>而非替代）
   * @param assetDocIds 资产引用（可为空表；不得含 null）
   */
  public static ToolResult ok(String message, List<String> assetDocIds) {
    return new ToolResult(null, message, assetDocIds);
  }

  /** 生成成功结果，附带一张由宿主解析的图片。 */
  public static ToolResult okImage(String message, ContentPart.Image image) {
    return okImages(message, List.of(image));
  }

  /** 生成成功结果，保留宿主传入的一张或多张图片的顺序。 */
  public static ToolResult okImages(String message, List<ContentPart.Image> images) {
    if (images.isEmpty()) {
      throw new IllegalArgumentException("图片结果至少需要一张图片");
    }
    return new ToolResult(null, message, List.of(), images);
  }

  public static ToolResult error(String code, String message) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("失败结果的 code 不能为空");
    }
    return new ToolResult(code, message, List.of());
  }

  public boolean success() {
    return code == null;
  }
}
