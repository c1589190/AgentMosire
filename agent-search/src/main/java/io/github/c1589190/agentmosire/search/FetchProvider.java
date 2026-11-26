// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

/** 抓取指定网页。实现负责实际访问地址及重定向约束。 */
@FunctionalInterface
public interface FetchProvider {

  /** 返回请求页面的标题和 Markdown 正文。 */
  FetchedPage fetch(FetchRequest request);

  /** 提供方保证访问仅限请求主机时返回 true，供受域约束的工具调用使用。 */
  default boolean confinesToRequestedHost() {
    return false;
  }
}
