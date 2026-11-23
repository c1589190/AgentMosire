// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import java.util.Objects;

/** 搜索词及期望结果数。提供方可以进一步限制搜索词长度。 */
public record SearchRequest(String query, int count) {

  public static final int DEFAULT_COUNT = 10;
  public static final int MAX_COUNT = 50;

  public SearchRequest {
    query = Objects.requireNonNull(query, "query").strip();
    if (query.isBlank()) {
      throw new IllegalArgumentException("query 必须为非空字符串");
    }
    if (count < 1 || count > MAX_COUNT) {
      throw new IllegalArgumentException("count 必须在 1 至 50 之间");
    }
  }
}
