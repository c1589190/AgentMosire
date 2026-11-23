package io.github.c1589190.agentlib.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** 由宿主绑定的实例及该插件的 Agent 配置。根 Agent 的父实例为空。 */
public record PluginAgent(String instanceId, String parentId, String lineage, JsonNode settings) {
  public PluginAgent {
    Objects.requireNonNull(instanceId, "instanceId");
    Objects.requireNonNull(lineage, "lineage");
    settings = settings == null ? null : settings.deepCopy();
  }

  @Override
  public JsonNode settings() {
    return settings == null ? null : settings.deepCopy();
  }
}
