// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.main.gateway.debug;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentlib.llm.ImageLimits;
import io.github.c1589190.agentlib.llm.ToolAsset;
import io.github.c1589190.agentlib.llm.VisionException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** 把不可信的 HTTP 附件解码成有界资产，诊断刻意不带请求原文。 */
final class ImageUploads {

  static final String IMAGE_BUSY = "IMAGE_BUSY";
  private static final int MAX_DIMENSION = 4096;
  private static final int MAX_BASE64_LENGTH = ((ImageLimits.MAX_IMAGE_BYTES + 2) / 3) * 4;
  private static final int PNG_IDAT = 0x49444154;
  private static final int PNG_IEND = 0x49454e44;
  private static final int PNG_IEND_CRC = 0xae426082;
  // WebP 容器标记使用 RIFF little-endian 布局。
  private static final int RIFF = 0x46464952;
  private static final int WEBP = 0x50424557;
  private static final int VP8 = 0x20385056;
  private static final int VP8L = 0x4c385056;
  private static final int VP8X = 0x58385056;
  private static final int ANIM = 0x4d494e41;
  private static final int ALPH = 0x48504c41;
  private static final int ANMF = 0x464d4e41;

  private ImageUploads() {}

  static List<ToolAsset> parse(JsonNode images) {
    if (images == null) {
      return List.of();
    }
    if (!images.isArray()) {
      throw invalid();
    }
    ImageLimits.checkCount(images.size());
    List<ToolAsset> result = new ArrayList<>(images.size());
    long total = 0;
    for (JsonNode item : images) {
      String mediaType = item.path("mediaType").textValue();
      String kind = format(mediaType);
      String data = item.path("data").textValue();
      if (kind == null || data == null || data.isEmpty() || data.length() % 4 != 0) {
        throw invalid();
      }
      if (data.length() > MAX_BASE64_LENGTH) {
        throw new VisionException(VisionException.IMAGE_LIMIT, "图片字节数超出请求限额");
      }
      byte[] bytes = decode(data);
      total += bytes.length;
      ImageLimits.checkBytes(bytes.length, total);
      checkFormat(kind, bytes);
      result.add(new ToolAsset(mediaType, bytes));
    }
    return List.copyOf(result);
  }

  private static byte[] decode(String value) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static String format(String mediaType) {
    return switch (mediaType) {
      case "image/png" -> "png";
      case "image/jpeg" -> "jpeg";
      case "image/gif" -> "gif";
      case "image/webp" -> "webp";
      case null, default -> null;
    };
  }

  private static void checkFormat(String kind, byte[] bytes) {
    if ("webp".equals(kind)) {
      checkWebp(bytes);
      return;
    }
    try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        throw invalid();
      }
      var reader = readers.next();
      try {
        if (!kind.equalsIgnoreCase(reader.getFormatName())) {
          throw invalid();
        }
        reader.setInput(in, true, true);
        checkDimensions(reader.getWidth(0), reader.getHeight(0));
        switch (kind) {
          case "png" -> checkPng(bytes);
          case "jpeg" -> checkJpeg(bytes);
          case "gif" -> checkGif(bytes);
          default -> throw invalid();
        }
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException failure) {
      throw invalid();
    }
  }

  private static void checkPng(byte[] bytes) {
    ByteBuffer in = ByteBuffer.wrap(bytes).position(8);
    boolean hasImageData = false;
    while (in.remaining() >= 12) {
      int size = in.getInt();
      int type = in.getInt();
      if (size < 0 || size > in.remaining() - 4) {
        throw invalid();
      }
      if (type == PNG_IEND) {
        if (!hasImageData || size != 0 || in.remaining() != 4 || in.getInt() != PNG_IEND_CRC) {
          throw invalid();
        }
        return;
      }
      hasImageData |= type == PNG_IDAT && size > 0;
      in.position(in.position() + size + 4);
    }
    throw invalid();
  }

  private static void checkJpeg(byte[] bytes) {
    // ImageIO 已识别 JPEG 尺寸，此处再确认扫描数据和图像结尾均存在。
    int end = bytes.length - 2;
    if (Byte.toUnsignedInt(bytes[end]) != 0xff || Byte.toUnsignedInt(bytes[end + 1]) != 0xd9) {
      throw invalid();
    }
    ByteBuffer in = ByteBuffer.wrap(bytes, 2, end - 2);
    while (in.remaining() >= 4) {
      if (Byte.toUnsignedInt(in.get()) != 0xff) {
        throw invalid();
      }
      int marker = Byte.toUnsignedInt(in.get());
      while (marker == 0xff && in.hasRemaining()) {
        marker = Byte.toUnsignedInt(in.get());
      }
      if (marker == 0x01) {
        continue;
      }
      if (marker == 0 || marker == 0xff || in.remaining() < 2) {
        throw invalid();
      }
      int size = Short.toUnsignedInt(in.getShort());
      if (size < 2 || size - 2 > in.remaining()) {
        throw invalid();
      }
      if (marker == 0xda) {
        if (size < 6 || size - 2 == in.remaining()) {
          throw invalid();
        }
        return;
      }
      in.position(in.position() + size - 2);
    }
    throw invalid();
  }

  private static void checkGif(byte[] bytes) {
    // 首帧可小于逻辑画布；遍历时也检查后续动画帧的尺寸及数据块边界。
    ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    checkDimensions(Short.toUnsignedInt(in.getShort(6)), Short.toUnsignedInt(in.getShort(8)));
    in.position(13);
    skipGifColorTable(in, bytes[10]);
    boolean hasFrame = false;
    while (in.hasRemaining()) {
      switch (Byte.toUnsignedInt(in.get())) {
        case 0x21 -> { // 扩展标签及可变长数据块
          skipBytes(in, 1);
          skipGifBlocks(in);
        }
        case 0x2c -> {
          int offset = in.position();
          skipBytes(in, 9);
          checkDimensions(
              Short.toUnsignedInt(in.getShort(offset + 4)),
              Short.toUnsignedInt(in.getShort(offset + 6)));
          skipGifColorTable(in, in.get(offset + 8));
          skipBytes(in, 1); // LZW 最小码长
          if (!skipGifBlocks(in)) {
            throw invalid();
          }
          hasFrame = true;
        }
        case 0x3b -> {
          if (!hasFrame || in.hasRemaining()) {
            throw invalid();
          }
          return;
        }
        default -> throw invalid();
      }
    }
    throw invalid();
  }

  private static void skipGifColorTable(ByteBuffer in, int flags) {
    if ((flags & 0x80) != 0) {
      skipBytes(in, 3 * (2 << (flags & 7)));
    }
  }

  private static boolean skipGifBlocks(ByteBuffer in) {
    boolean hasData = false;
    while (in.hasRemaining()) {
      int size = Byte.toUnsignedInt(in.get());
      if (size == 0) {
        return hasData;
      }
      skipBytes(in, size);
      hasData = true;
    }
    throw invalid();
  }

  private static void skipBytes(ByteBuffer in, int size) {
    if (size > in.remaining()) {
      throw invalid();
    }
    in.position(in.position() + size);
  }

  private static void checkWebp(byte[] bytes) {
    ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (in.remaining() < 20
        || in.getInt() != RIFF
        || Integer.toUnsignedLong(in.getInt()) != bytes.length - 8
        || in.getInt() != WEBP) {
      throw invalid();
    }
    int first = in.getInt(12);
    if (first != VP8 && first != VP8L && first != VP8X) {
      throw invalid();
    }
    boolean animated = false;
    boolean animationHeader = false;
    boolean picture = false;
    int canvasWidth = 0;
    int canvasHeight = 0;
    while (in.remaining() >= 8) {
      int tag = in.getInt();
      long size = Integer.toUnsignedLong(in.getInt());
      if (size + (size & 1) > in.remaining()) {
        throw invalid();
      }
      int start = in.position();
      if (tag == VP8X) {
        if (start != 20 || size != 10) {
          throw invalid();
        }
        animated = (in.get(start) & 0x02) != 0;
        canvasWidth = u24(in, start + 4) + 1;
        canvasHeight = u24(in, start + 7) + 1;
        checkDimensions(canvasWidth, canvasHeight);
      } else if (!animated && (tag == VP8 || tag == VP8L)) {
        if (picture) {
          throw invalid();
        }
        checkWebpFrame(in, tag, start, size);
        picture = true;
      } else if (animated && tag == ANIM) {
        if (animationHeader || size != 6) {
          throw invalid();
        }
        animationHeader = true;
      } else if (animated && tag == ANMF) {
        if (!animationHeader || size < 24) {
          throw invalid();
        }
        int frameWidth = u24(in, start + 6) + 1;
        int frameHeight = u24(in, start + 9) + 1;
        checkDimensions(frameWidth, frameHeight);
        if (2 * u24(in, start) + frameWidth > canvasWidth
            || 2 * u24(in, start + 3) + frameHeight > canvasHeight) {
          throw invalid();
        }
        checkAnimationFrame(in.slice(start + 16, (int) size - 16).order(ByteOrder.LITTLE_ENDIAN));
        picture = true;
      }
      in.position(start + (int) (size + (size & 1)));
    }
    if (in.hasRemaining() || !picture) {
      throw invalid();
    }
  }

  private static void checkAnimationFrame(ByteBuffer frame) {
    int tag = frame.getInt();
    long size = Integer.toUnsignedLong(frame.getInt());
    long padded = size + (size & 1);
    if (tag == ALPH) {
      if (padded > frame.remaining() - 8) {
        throw invalid();
      }
      frame.position(frame.position() + (int) padded);
      tag = frame.getInt();
      size = Integer.toUnsignedLong(frame.getInt());
      padded = size + (size & 1);
    }
    if ((tag != VP8 && tag != VP8L) || padded != frame.remaining()) {
      throw invalid();
    }
    checkWebpFrame(frame, tag, frame.position(), size);
  }

  private static void checkWebpFrame(ByteBuffer in, int tag, int start, long size) {
    if (tag == VP8L) {
      if (size <= 5 || Byte.toUnsignedInt(in.get(start)) != 0x2f) {
        throw invalid();
      }
      int header = in.getInt(start + 1);
      if ((header >>> 29) != 0) {
        throw invalid();
      }
      checkDimensions((header & 0x3fff) + 1, ((header >>> 14) & 0x3fff) + 1);
    } else {
      if (size <= 10 || (in.get(start) & 1) != 0 || u24(in, start + 3) != 0x2a019d) {
        throw invalid();
      }
      checkDimensions(in.getShort(start + 6) & 0x3fff, in.getShort(start + 8) & 0x3fff);
    }
  }

  private static int u24(ByteBuffer in, int offset) {
    return Byte.toUnsignedInt(in.get(offset))
        | Byte.toUnsignedInt(in.get(offset + 1)) << 8
        | Byte.toUnsignedInt(in.get(offset + 2)) << 16;
  }

  private static void checkDimensions(int width, int height) {
    if (width < 1 || height < 1) {
      throw invalid();
    }
    if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
      throw new VisionException(VisionException.IMAGE_LIMIT, "图片宽高超出输入限额");
    }
  }

  private static VisionException invalid() {
    return new VisionException(
        VisionException.IMAGE_INVALID, "images 必须包含有效的 PNG、JPEG、GIF 或 WebP Base64 图片");
  }
}
