// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.agentlib.llm;

/** 一次内联视觉请求的预算，由发送方和宿主输入入口共用。 */
public final class ImageLimits {

  /** 单个请求内图片分片数，包含回放的历史图片。 */
  public static final int MAX_IMAGES = 8;

  /** 单张图片的原始字节上限。 */
  public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

  /** 所有图片的原始字节上限。 */
  public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;

  /** 带图请求序列化后的字节上限，包含 Base64、文本和 JSON。 */
  public static final int MAX_REQUEST_BYTES = 12 * 1024 * 1024;

  private ImageLimits() {}

  /** 校验图片数量，先于读取或解码各个图片。 */
  public static void checkCount(int count) {
    if (count > MAX_IMAGES) {
      throw new VisionException(VisionException.IMAGE_LIMIT, "图片数量超出请求限额");
    }
  }

  /** 校验单张图片与已累计的原始字节数，先于 Base64 编码。 */
  public static void checkBytes(int imageBytes, long totalBytes) {
    if (imageBytes > MAX_IMAGE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
      throw new VisionException(VisionException.IMAGE_LIMIT, "图片字节数超出请求限额");
    }
  }
}
