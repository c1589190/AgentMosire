// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.main.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.config.ConfigStore;
import io.github.c1589190.agentmosire.agentlib.permission.CommandMode;
import io.github.c1589190.agentmosire.agentlib.permission.CommandModeLoader;
import io.github.c1589190.agentmosire.agentlib.permission.ResourceScope;
import io.github.c1589190.agentmosire.agentlib.permission.RoleSet;
import io.github.c1589190.agentmosire.brain.decision.DecisionWindows;
import io.github.c1589190.agentmosire.brain.permission.AgentRoles;
import io.github.c1589190.agentmosire.brain.skills.SkillSettings;
import io.github.c1589190.agentmosire.brain.subagent.SubagentLimits;
import io.github.c1589190.agentmosire.brain.subagent.SubagentLimitsLoader;
import io.github.c1589190.agentmosire.brain.tools.WorkingDirsLoader;
import java.nio.file.Path;

/** 从 Main 的配置存储读取运行时设置字段，由各字段的所有者校验和构造。 */
public final class RuntimeSettings {

  private RuntimeSettings() {}

  /** 主服务和子进程都使用各自配置根读取决策窗口。 */
  public static DecisionWindows decisionWindows(ConfigStore store) {
    return DecisionWindows.parse(
        field(store, DecisionWindows.DEADLINE_KEY), field(store, DecisionWindows.COMMIT_WAIT_KEY));
  }

  /** 读取主 Agent 的初始命令档位。 */
  public static CommandMode commandMode(ConfigStore store) {
    return CommandModeLoader.parse(
        store.get(CommandModeLoader.SECTION, CommandModeLoader.KEY_MODE).orElse(null));
  }

  /** 读取主服务的子 Agent 额度。 */
  public static SubagentLimits subagentLimits(ConfigStore store) {
    return SubagentLimitsLoader.parse(
        field(store, SubagentLimits.KEY_MAX_DEPTH),
        field(store, SubagentLimits.KEY_MAX_CHILDREN),
        field(store, SubagentLimits.KEY_MAX_INSTANCES));
  }

  /** 读取主 Agent 的工作目录范围。 */
  public static ResourceScope workingDirs(ConfigStore store) {
    return WorkingDirsLoader.parse(
        store.get(WorkingDirsLoader.SECTION, WorkingDirsLoader.KEY).orElse(null));
  }

  /** 读取主 Agent 的角色。 */
  public static RoleSet roles(ConfigStore store) {
    return AgentRoles.parse(store.get(AgentRoles.SECTION, AgentRoles.KEY).orElse(null));
  }

  /** 读取主服务的技能目录设置，目录缺省值由宿主提供。 */
  public static SkillSettings skills(ConfigStore store, Path defaultRoot) {
    return SkillSettings.parse(
        store.get(SkillSettings.SECTION, SkillSettings.KEY_DIR).orElse(null),
        store.get(SkillSettings.SECTION, SkillSettings.KEY_LISTING_BUDGET).orElse(null),
        store.get(SkillSettings.SECTION, SkillSettings.KEY_PRELOAD).orElse(null),
        defaultRoot);
  }

  private static JsonNode field(ConfigStore store, String fullKey) {
    int dot = fullKey.indexOf('.');
    return store.get(fullKey.substring(0, dot), fullKey.substring(dot + 1)).orElse(null);
  }
}
