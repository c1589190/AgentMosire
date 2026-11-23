// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import java.util.List;

/** 提供结构化网页搜索结果。实例由创建它的宿主负责关闭。 */
@FunctionalInterface
public interface SearchProvider {

  /** 按相关度返回结果。空列表表示没有匹配项。 */
  List<SearchHit> search(SearchRequest request);
}
