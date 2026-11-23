// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.brain.skills;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.config.ConfigException;
import io.github.c1589190.agentmosire.agentlib.config.ConfigStore;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 把配置里的 {@code skills.*} 读成技能的装配参数（读法照 {@code WorkingDirsLoader} / {@code SubagentLimitsLoader}）。
 *
 * <p>三档语义与目录围栏同一口径（"没写"与"显式写空"是两种取值）：
 *
 * <ul>
 *   <li>{@code skills.dir}：技能根目录。<b>整项缺失 ⇒ 用缺省根</b>（装配层给的 {@code <dataDir>/skills}）——技能是
 *       “有就用、没有就是空目录”的可选能力，缺省根不存在不是错误；项存在但值不是非空路径文本 ⇒ 启动期<i>响亮失败</i> （{@link
 *       #E_SKILLS}）：把根配成一个数字然后静默回落到缺省根，等于“看起来配了、其实按别的办”；
 *   <li>{@code skills.listingBudget}：目录档字符预算（{@link SkillCatalog}）。缺失 ⇒ {@link
 *       SkillCatalog#DEFAULT_LISTING_BUDGET}；<b>0 是合法取值</b>（不披露目录档），负数/非整数响亮失败；
 *   <li>{@code skills.preload}：每回合预载正文的技能名（对应 {@code AgentSpec.skills} 的接线面）。缺失或空数组 ⇒
 *       一个都不预载；元素必须非空文本。名字在目录里不存在/不在可见面内时由装配器跳过（陈旧名字不该让回合崩）。
 * </ul>
 *
 * @param root 技能根目录（绝对化；存在时取 realpath——与权限前缀同源）
 * @param listingBudget 目录档字符预算（≥0）
 * @param preloaded 预载技能名集合（不可变）
 */
public record SkillSettings(Path root, int listingBudget, Set<String> preloaded) {

  public SkillSettings {
    Objects.requireNonNull(root, "root");
    if (listingBudget < 0) {
      throw new IllegalArgumentException("skills.listingBudget 不能为负: " + listingBudget);
    }
    preloaded = Set.copyOf(preloaded == null ? Set.of() : preloaded);
  }

  /**
   * 读装配参数。
   *
   * @param store 配置存储（本进程配置根上的实例）
   * @param defaultRoot 缺省技能根（装配层给：{@code <dataDir>/skills}）
   * @throws ConfigException {@code E_SKILLS}（任一项存在但取值非法）
   */
  public static SkillSettings load(ConfigStore store, Path defaultRoot) {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(defaultRoot, "defaultRoot");
    return parse(
        store.get(SECTION, KEY_DIR).orElse(null),
        store.get(SECTION, KEY_LISTING_BUDGET).orElse(null),
        store.get(SECTION, KEY_PRELOAD).orElse(null),
        defaultRoot);
  }

  /** 校验宿主已读取的技能目录、字符预算和预载列表，并规范化有效目录。 */
  public static SkillSettings parse(
      JsonNode directory, JsonNode listingBudget, JsonNode preload, Path defaultRoot) {
    Objects.requireNonNull(defaultRoot, "defaultRoot");
    Path root = canonical(readDir(directory, defaultRoot));
    return new SkillSettings(root, readBudget(listingBudget), readPreload(preload));
  }

  private static Path readDir(JsonNode value, Path defaultRoot) {
    if (value == null || value.isNull()) {
      return defaultRoot;
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw invalid("必须是技能根目录的路径文本", value.toString());
    }
    try {
      return Path.of(value.asText().strip());
    } catch (RuntimeException e) {
      throw invalid("目录路径不可用: " + e.getMessage(), value.toString());
    }
  }

  private static int readBudget(JsonNode value) {
    if (value == null || value.isNull()) {
      return SkillCatalog.DEFAULT_LISTING_BUDGET;
    }
    if (!value.isInt() && !value.isLong()) {
      throw invalid("必须是整数（0 = 不披露目录档，负数非法）", value.toString());
    }
    int budget = value.asInt();
    if (budget < 0) {
      throw invalid("不能为负", value.toString());
    }
    return budget;
  }

  private static Set<String> readPreload(JsonNode value) {
    if (value == null || value.isNull()) {
      return Set.of();
    }
    if (!value.isArray()) {
      throw invalid("必须是技能名数组", value.toString());
    }
    Set<String> names = new LinkedHashSet<>();
    for (JsonNode item : value) {
      if (!item.isTextual() || item.asText().isBlank()) {
        throw invalid("数组元素必须是技能名文本", value.toString());
      }
      names.add(item.asText().strip());
    }
    return Set.copyOf(names);
  }

  private static Path canonical(Path root) {
    return SkillAccess.canonicalRoot(root);
  }

  private static ConfigException invalid(String reason, String rawValue) {
    return new ConfigException(
        E_SKILLS, "技能配置不可用：" + FULL_KEY_PREFIX + " " + reason + "（实际值: " + rawValue + "）");
  }

  /** {@code skills} 段里的键：目录。 */
  public static final String KEY_DIR = "dir";

  /** {@code skills} 段里的键：目录档字符预算。 */
  public static final String KEY_LISTING_BUDGET = "listingBudget";

  /** {@code skills} 段里的键：预载技能名。 */
  public static final String KEY_PRELOAD = "preload";

  /** 配置段名。 */
  public static final String SECTION = "skills";

  /** 配置项非法（类型/取值）。消息只含键名与原值（本项无密钥）。 */
  public static final String E_SKILLS = "E_SKILLS";

  /** 消息里的键名前缀（三段式全名在消息里逐项拼出，方便直接指出是哪一项）。 */
  public static final String FULL_KEY_PREFIX = SECTION + ".*";
}
