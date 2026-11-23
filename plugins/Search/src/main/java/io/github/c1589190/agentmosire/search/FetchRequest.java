// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 经过语法初筛的公开 HTTP(S) 网页请求。实际地址和重定向由抓取提供方约束。 */
public record FetchRequest(URI url) {

  private static final int MAX_URL_LENGTH = 8192;
  private static final int MAX_HOST_LENGTH = 253;
  private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern TLD = Pattern.compile("[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Set<String> RESERVED_SUFFIXES =
      Set.of(
          "arpa",
          "example",
          "home",
          "internal",
          "invalid",
          "lan",
          "local",
          "localdomain",
          "localhost",
          "onion",
          "test");

  public FetchRequest {
    Objects.requireNonNull(url, "url");
    String scheme = url.getScheme();
    String host = url.getHost();
    if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)
        || host == null
        || url.getRawUserInfo() != null
        || url.toASCIIString().length() > MAX_URL_LENGTH) {
      throw invalidUrl();
    }
    int port = url.getPort();
    String authority = port == -1 ? host : host + ":" + port;
    if (port == 0 || port > 65535 || !authority.equalsIgnoreCase(url.getRawAuthority())) {
      throw invalidUrl();
    }
    requirePublicHost(host.toLowerCase(Locale.ROOT));
  }

  /** 解析网页地址并把无效输入映射为固定错误信息。 */
  public static FetchRequest of(String value) {
    if (value == null || value.length() > MAX_URL_LENGTH) {
      throw invalidUrl();
    }
    try {
      return new FetchRequest(new URI(value));
    } catch (URISyntaxException e) {
      throw invalidUrl();
    }
  }

  /** 供权限检查使用的规范小写 DNS 名。 */
  public String host() {
    return url.getHost().toLowerCase(Locale.ROOT);
  }

  private static void requirePublicHost(String host) {
    String[] labels = host.split("\\.", -1);
    String suffix = labels[labels.length - 1];
    if (host.length() > MAX_HOST_LENGTH
        || labels.length < 2
        || !TLD.matcher(suffix).matches()
        || RESERVED_SUFFIXES.contains(suffix)) {
      throw invalidUrl();
    }
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        throw invalidUrl();
      }
    }
  }

  private static IllegalArgumentException invalidUrl() {
    return new IllegalArgumentException("url 须为带公开 DNS 主机名的 HTTP(S) 地址");
  }
}
