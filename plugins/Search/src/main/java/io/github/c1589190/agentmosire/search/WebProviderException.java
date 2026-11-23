// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import java.util.Objects;

/** 稳定的网页提供方错误。消息可以回给模型及运维账目。 */
public final class WebProviderException extends RuntimeException {

  private final Kind kind;

  public WebProviderException(Kind kind) {
    super(Objects.requireNonNull(kind, "kind").message);
    this.kind = kind;
  }

  /** 供 Java 宿主按错误类型决定重试或向用户提示。 */
  public Kind kind() {
    return kind;
  }

  /** 网页请求失败类别及与工具结果对应的错误码。 */
  public enum Kind {
    INVALID_REQUEST("网页请求参数无效"),
    AUTH("网页提供方鉴权失败"),
    PAYMENT_REQUIRED("网页提供方余额不足"),
    UNAVAILABLE("请求的网页无法访问"),
    RATE_LIMITED("网页提供方请求过于频繁"),
    SCOPE_UNSUPPORTED("抓取提供方无法确保请求域限制"),
    RESPONSE_TOO_LARGE("网页结果超出工具响应预算"),
    TIMEOUT("网页请求超时"),
    INTERRUPTED("网页请求被取消"),
    PROTOCOL("网页提供方响应无效"),
    UPSTREAM("网页提供方暂不可用");

    private final String message;

    Kind(String message) {
      this.message = message;
    }

    public String code() {
      return "WEB_" + name();
    }
  }
}
