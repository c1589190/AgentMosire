// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.search;

import java.net.URI;
import java.util.Objects;

/** 一个网页搜索结果。日期未知时 {@code date} 可为空。 */
public record SearchHit(URI url, String title, String snippet, String date) {

  public SearchHit {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(snippet, "snippet");
  }
}
