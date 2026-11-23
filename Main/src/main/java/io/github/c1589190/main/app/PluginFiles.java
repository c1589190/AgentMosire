package io.github.c1589190.main.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentlib.plugin.HostServices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Host convention for file-backed plugin inputs. */
final class PluginFiles {
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]*");
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

  private PluginFiles() {}

  static HostServices load(
      Path plugins,
      Path data,
      String id,
      JsonNode legacySettings,
      Set<String> excludedEnvironment) {
    if (!ID.matcher(id).matches()) {
      throw new IllegalArgumentException("非法插件 ID: " + id);
    }
    Path directory = plugins.resolve(id);
    try {
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectory(directory);
      }
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("插件配置目录必须是实体目录: " + directory);
      }
    } catch (IOException failure) {
      throw new IllegalArgumentException("无法创建插件配置目录: " + directory, failure);
    }
    JsonNode settings = readObject(directory.resolve("config.json"));
    return new HostServices(
        null,
        settings == null ? legacySettings : settings,
        Map.of(),
        excludedEnvironment,
        directory,
        data.toAbsolutePath().normalize(),
        null);
  }

  static JsonNode mainAgentSettings(Path data) {
    JsonNode config = readObject(data.resolve("agentconfigs").resolve("main.json"));
    JsonNode plugins = config == null ? null : config.get("plugins");
    if (plugins != null && !plugins.isObject()) {
      throw new IllegalArgumentException("agentconfigs/main.json 的 plugins 必须是 JSON 对象");
    }
    return plugins;
  }

  private static JsonNode readObject(Path file) {
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("配置必须是实体文件: " + file);
    }
    try {
      JsonNode value = JSON.readTree(Files.readString(file));
      if (value != null && value.isObject()) {
        return value;
      }
    } catch (IOException failure) {
      throw new IllegalArgumentException("无法读取 JSON 配置: " + file, failure);
    }
    throw new IllegalArgumentException("配置必须是 JSON 对象: " + file);
  }
}
