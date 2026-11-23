// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.agentlib.permission;

/**
 * Agent 的身份档位，按默认权限从低到高排列。
 *
 * <ul>
 *   <li>{@link #READONLY}：只读默认档位。
 *   <li>{@link #DEFAULT}：普通运行档位。
 *   <li>{@link #SYSTEM}：系统运行档位。
 * </ul>
 */
public enum AccessToken {
  READONLY(0),
  DEFAULT(1),
  SYSTEM(2);

  private final int rank;

  AccessToken(int rank) {
    this.rank = rank;
  }

  /** 本令牌是否至少达到 {@code other} 的级别（用于"工具要求 X 级 → 调用者是否够格"）。 */
  public boolean atLeast(AccessToken other) {
    return rank >= other.rank;
  }
}
