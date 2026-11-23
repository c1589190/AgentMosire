// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentlib.llm;

import java.util.Objects;

/**
 * 模型路由：一个命名入口 → baseUrl/model + 密钥引用 + 接法。
 *
 * <p>凭据引用是由宿主解析的纯字段，例如文件键 {@code keys.*} 或显式环境引用 {@code env:NAME}。 宿主将取得凭据的接口另行传给客户端，路由只保存引用。
 *
 * <p><b>{@code baseUrl} 的语义（A3 ③：不写清楚每个人都会踩）</b>：它是 <b>API 根</b>，不是补全端点。 客户端会在其后拼 {@code
 * /chat/completions}：
 *
 * <ul>
 *   <li>{@code https://api.deepseek.com/v1} → {@code POST
 *       https://api.deepseek.com/v1/chat/completions} ✅
 *   <li>{@code https://api.deepseek.com} → {@code POST https://api.deepseek.com/chat/completions}
 *       ⚠️ 多数供应商要求 {@code /v1} 前缀，<b>必须由调用方写进 baseUrl</b>（本库不替任何人猜 {@code /v1}：有的网关就挂在根上，有的用 {@code
 *       /openai/v1} 这类前缀，替猜等于替用户写错）
 *   <li>{@code https://api.deepseek.com/v1/chat/completions} → 拼成 {@code
 *       …/chat/completions/chat/completions} ❌ 这类"把端点当根写进来"的形态在构造期<b>响亮拒绝</b>（见 {@link
 *       OpenAICompatibleLlmClient}），不静默纠正
 * </ul>
 *
 * <p>末尾斜杠会被容忍（去掉后再拼），其余一律由构造期校验兜住。
 */
public record ModelRoute(
    String name, String baseUrl, String model, String credentialsRef, LlmTransport transport) {

  public ModelRoute {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(model, "model");
    // credentialsRef 允许空：匿名/本地部署（如 Ollama）无密钥
    // transport 允许 null：按缺省接法补齐（见下）——"没写接法"就是"用默认接法"，不需要每层问一次
    transport = transport == null ? LlmTransport.defaults() : transport;
  }

  /** 缺省接法（{@link LlmTransport#defaults()}）：老调用点逐字不动。 */
  public ModelRoute(String name, String baseUrl, String model, String credentialsRef) {
    this(name, baseUrl, model, credentialsRef, LlmTransport.defaults());
  }

  public static ModelRoute of(String name, String baseUrl, String model, String credentialsRef) {
    return new ModelRoute(name, baseUrl, model, credentialsRef);
  }

  public static ModelRoute of(
      String name, String baseUrl, String model, String credentialsRef, LlmTransport transport) {
    return new ModelRoute(name, baseUrl, model, credentialsRef, transport);
  }
}
