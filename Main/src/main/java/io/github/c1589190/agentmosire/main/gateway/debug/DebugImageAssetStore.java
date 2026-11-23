// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.main.gateway.debug;

import io.github.c1589190.agentmosire.agentlib.llm.ContentPart;
import io.github.c1589190.agentmosire.agentlib.llm.ImageLimits;
import io.github.c1589190.agentmosire.agentlib.llm.ToolAsset;
import io.github.c1589190.agentmosire.agentlib.llm.ToolAssetResolver;
import io.github.c1589190.agentmosire.agentlib.llm.VisionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 主进程私有的图片资产目录。解析权限随当前运行时会话变化。 */
public final class DebugImageAssetStore implements ToolAssetResolver {

  private static final Logger LOG = LoggerFactory.getLogger(DebugImageAssetStore.class);
  private static final Pattern ASSET_ID =
      Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\.(png|jpeg|gif|webp)");

  private final Path root;
  private final boolean posix;
  private final AtomicReference<Supplier<String>> currentConversation = new AtomicReference<>();

  /** 解析器在模型装配时即能注入，目录在第一次附图时才创建。 */
  public DebugImageAssetStore(Path dataDirectory) {
    root = dataDirectory.toAbsolutePath().normalize().resolve("image-assets");
    posix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /** 将解析器绑定到主运行时唯一的当前会话指针。 */
  public void bindConversation(Supplier<String> conversation) {
    if (!currentConversation.compareAndSet(null, Objects.requireNonNull(conversation))) {
      throw new IllegalStateException("图片资产仓已绑定会话来源");
    }
  }

  /** 只解析当前会话下由宿主格式生成的资产 id，绝不把任意路径交给文件系统。 */
  @Override
  public Optional<ToolAsset> resolve(String assetId) {
    var match = ASSET_ID.matcher(assetId);
    if (!match.matches()) {
      return Optional.empty();
    }
    Path session = sessionDirectory();
    if (!existingSession(session)) {
      return Optional.empty();
    }
    Path path = session.resolve(assetId);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = in.readNBytes(ImageLimits.MAX_IMAGE_BYTES + 1);
      if (bytes.length == 0 || bytes.length > ImageLimits.MAX_IMAGE_BYTES) {
        return Optional.empty();
      }
      return Optional.of(new ToolAsset("image/" + match.group(1), bytes));
    } catch (IOException failure) {
      return Optional.empty();
    }
  }

  /** 在串行对话执行器内创建本批引用；部分写入失败时回滚已写文件。 */
  List<ContentPart.Image> persist(List<ToolAsset> uploads) {
    Path session = sessionDirectory();
    if (session == null) {
      throw unavailable();
    }
    var images = new ArrayList<ContentPart.Image>(uploads.size());
    boolean complete = false;
    try {
      createPrivateDirectory(root);
      createPrivateDirectory(session);
      for (ToolAsset upload : uploads) {
        var image =
            new ContentPart.Image(upload.mediaType(), UUID.randomUUID() + "." + suffix(upload));
        writeFile(session, image.assetId(), upload);
        images.add(image);
      }
      List<ContentPart.Image> result = List.copyOf(images);
      complete = true;
      return result;
    } catch (IOException failure) {
      throw unavailable();
    } finally {
      if (!complete) {
        discard(session, images);
      }
    }
  }

  /** 当回合在写入历史之前明确终止时回收本批图片。仅由串行对话执行器调用。 */
  void discard(List<ContentPart.Image> images) {
    discard(sessionDirectory(), images);
  }

  private void discard(Path session, List<ContentPart.Image> images) {
    if (!existingSession(session)) {
      return;
    }
    for (ContentPart.Image image : images) {
      try {
        Files.deleteIfExists(session.resolve(image.assetId()));
      } catch (IOException failure) {
        LOG.warn("无法清理尚未进入会话的图片资产");
      }
    }
  }

  private void writeFile(Path session, String assetId, ToolAsset upload) throws IOException {
    Path temporary = Files.createTempFile(session, ".upload-", ".tmp", permissions("rw-------"));
    try {
      Files.write(temporary, upload.bytes(), LinkOption.NOFOLLOW_LINKS);
      Files.move(temporary, session.resolve(assetId), StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private FileAttribute<?>[] permissions(String mode) {
    return posix
        ? new FileAttribute<?>[] {
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))
        }
        : new FileAttribute<?>[0];
  }

  private void createPrivateDirectory(Path path) throws IOException {
    try {
      Files.createDirectory(path, permissions("rwx------"));
    } catch (FileAlreadyExistsException existing) {
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw existing;
      }
    }
  }

  private Path sessionDirectory() {
    Supplier<String> supplier = currentConversation.get();
    return supplier == null ? null : root.resolve(directoryName(supplier.get()));
  }

  private static String directoryName(String id) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("宿主缺少 SHA-256", impossible);
    }
  }

  private boolean existingSession(Path session) {
    return session != null
        && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        && Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS);
  }

  private static String suffix(ToolAsset upload) {
    return switch (upload.mediaType()) {
      case "image/png" -> "png";
      case "image/jpeg" -> "jpeg";
      case "image/gif" -> "gif";
      case "image/webp" -> "webp";
      default -> throw new VisionException(VisionException.IMAGE_INVALID, "图片媒体类型无效");
    };
  }

  private static VisionException unavailable() {
    return new VisionException(VisionException.IMAGE_UNAVAILABLE, "当前会话无法保存图片资产");
  }
}
