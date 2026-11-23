// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.search;

import java.util.Objects;

/** 提供方返回的网页标题和 Markdown 正文。标题未知时使用空字符串。 */
public record FetchedPage(String title, String content) {

  public FetchedPage {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(content, "content");
  }
}
