// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentlib.llm;

import java.util.Objects;

/** 图片被发送前的视觉输入错误。稳定错误码供宿主映射到自身接口。 */
public final class VisionException extends LlmException {

  /** 当前路由未声明图片输入能力。 */
  public static final String VISION_UNSUPPORTED = "VISION_UNSUPPORTED";

  /** 图片引用无法由当前宿主解析。 */
  public static final String IMAGE_UNAVAILABLE = "IMAGE_UNAVAILABLE";

  /** 图片格式、媒体类型或消息角色不符合视觉输入契约。 */
  public static final String IMAGE_INVALID = "IMAGE_INVALID";

  /** 图片数量或字节量超出内联请求限额。 */
  public static final String IMAGE_LIMIT = "IMAGE_LIMIT";

  private final String code;

  /**
   * 创建视觉输入错误。
   *
   * @param code 稳定错误码
   * @param message 可外显诊断，不能含图片字节或编码内容
   */
  public VisionException(String code, String message) {
    super(message, Kind.CONFIG);
    this.code = Objects.requireNonNull(code, "code");
  }

  /** 返回可供调用方识别和转发的错误码。 */
  public String code() {
    return code;
  }
}
