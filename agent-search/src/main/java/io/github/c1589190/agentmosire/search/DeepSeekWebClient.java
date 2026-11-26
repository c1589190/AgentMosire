// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.search.WebProviderException.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** DeepSeek 的独立 Search/Fetch HTTP 客户端。实例由创建方关闭。 */
public final class DeepSeekWebClient implements SearchProvider, FetchProvider, AutoCloseable {

  private static final URI OFFICIAL_ORIGIN = URI.create("https://api.deepseek.com");
  private static final String SEARCH_PATH = "/v1/tools/search";
  private static final String FETCH_PATH = "/v1/tools/fetch";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration RETRY_DELAY = Duration.ofMillis(250);
  private static final int DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  private static final int MAX_QUERY_LENGTH = 128;
  private static final Pattern BEARER = Pattern.compile("[A-Za-z0-9._~+/=-]+");
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private final URI origin;
  private final Supplier<String> keySource;
  private final Duration timeout;
  private final int maxResponseBytes;
  private final HttpClient http;

  /** 连接 DeepSeek 官方 HTTPS 地址。每次调用从凭据提供方重新读取密钥。 */
  public DeepSeekWebClient(Supplier<String> keySource) {
    this(OFFICIAL_ORIGIN, keySource, DEFAULT_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
  }

  private DeepSeekWebClient(
      URI origin, Supplier<String> keySource, Duration timeout, int maxResponseBytes) {
    this.origin = origin;
    this.keySource = Objects.requireNonNull(keySource, "keySource");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero() || maxResponseBytes < 1) {
      throw new IllegalArgumentException("时限和响应字节上限须为正数");
    }
    this.maxResponseBytes = maxResponseBytes;
    var builder =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout.compareTo(CONNECT_TIMEOUT) < 0 ? timeout : CONNECT_TIMEOUT);
    if (!OFFICIAL_ORIGIN.equals(origin)) {
      builder.proxy(HttpClient.Builder.NO_PROXY);
    }
    this.http = builder.build();
  }

  /** 可信 Java 宿主可接回环协议夹具。地址必须是 HTTP(S) 回环字面量且只含 origin。 */
  public static DeepSeekWebClient forLoopback(
      URI origin, Supplier<String> keySource, Duration timeout, int maxResponseBytes) {
    Objects.requireNonNull(origin, "origin");
    String host = origin.getHost();
    String path = origin.getRawPath();
    if (!"http".equalsIgnoreCase(origin.getScheme())
            && !"https".equalsIgnoreCase(origin.getScheme())
        || !"127.0.0.1".equals(host) && !"[::1]".equals(host)
        || origin.getRawUserInfo() != null
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || !"".equals(path) && !"/".equals(path)
        || origin.getPort() < 1
        || origin.getPort() > 65535) {
      throw new IllegalArgumentException("夹具地址须为带端口的 HTTP(S) 回环 origin");
    }
    return new DeepSeekWebClient(origin, keySource, timeout, maxResponseBytes);
  }

  @Override
  public SearchResults search(SearchRequest request) {
    if (request.query().codePointCount(0, request.query().length()) > MAX_QUERY_LENGTH) {
      throw failure(Kind.INVALID_REQUEST);
    }
    ObjectNode payload =
        JSON.createObjectNode().put("query", request.query()).put("count", request.count());
    return exchange(
        SEARCH_PATH,
        payload,
        request.query(),
        (response, key) -> searchResults(response, key, request.count()));
  }

  @Override
  public FetchedPage fetch(FetchRequest request) {
    ObjectNode payload = JSON.createObjectNode().put("url", request.url().toASCIIString());
    return exchange(
        FETCH_PATH,
        payload,
        request.url().toASCIIString(),
        (response, key) ->
            new FetchedPage(
                redact(requiredText(response, "title"), key),
                redact(requiredText(response, "content"), key)));
  }

  @Override
  public void close() {
    http.shutdownNow();
  }

  static String requireKey(Supplier<String> keySource) {
    String key;
    try {
      key = Objects.requireNonNullElse(keySource.get(), "");
    } catch (RuntimeException e) {
      throw failure(Kind.AUTH);
    }
    if (!BEARER.matcher(key).matches()) {
      throw failure(Kind.AUTH);
    }
    return key;
  }

  private <T> T exchange(
      String path, ObjectNode payload, String argument, BiFunction<JsonNode, String, T> decode) {
    String key = requireKey(keySource);
    if (argument.contains(key)) {
      throw failure(Kind.INVALID_REQUEST);
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    String json = payload.toString();
    HttpResponse<byte[]> response = send(path, json, key, deadline);
    int status = response.statusCode();
    if ((status == 429 || status >= 500 && status <= 599) && pauseBeforeRetry(response, deadline)) {
      response = send(path, json, key, deadline);
      status = response.statusCode();
    }
    if (status != 200) {
      throw failure(statusKind(status));
    }
    try {
      JsonNode body = JSON.readTree(response.body());
      if (body == null || !body.isObject()) {
        throw failure(Kind.PROTOCOL);
      }
      return decode.apply(body, key);
    } catch (JacksonException e) {
      throw failure(Kind.PROTOCOL);
    }
  }

  private HttpResponse<byte[]> send(String path, String json, String key, long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw failure(Kind.TIMEOUT);
    }
    HttpRequest request =
        HttpRequest.newBuilder(origin.resolve(path))
            .timeout(Duration.ofNanos(remaining))
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    CompletableFuture<HttpResponse<byte[]>> pending =
        http.sendAsync(request, info -> new BoundedBody(maxResponseBytes));
    try {
      return pending.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw failure(Kind.TIMEOUT);
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw failure(Kind.INTERRUPTED);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof WebProviderException failure) {
        throw failure;
      }
      throw failure(e.getCause() instanceof HttpTimeoutException ? Kind.TIMEOUT : Kind.UPSTREAM);
    }
  }

  private static boolean pauseBeforeRetry(HttpResponse<byte[]> response, long deadline) {
    Duration pause = RETRY_DELAY;
    var retryAfter = response.headers().firstValue("Retry-After");
    if (retryAfter.isPresent()) {
      try {
        long seconds = Long.parseLong(retryAfter.orElseThrow());
        if (seconds > 0) {
          pause = Duration.ofSeconds(seconds);
        }
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (pause.compareTo(Duration.ofNanos(deadline - System.nanoTime())) >= 0) {
      return false;
    }
    try {
      Thread.sleep(pause);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failure(Kind.INTERRUPTED);
    }
  }

  private static SearchResults searchResults(JsonNode body, String key, int count) {
    JsonNode results = body.path("results");
    if (!results.isArray()) {
      throw failure(Kind.PROTOCOL);
    }
    List<SearchHit> hits = new ArrayList<>();
    int skipped = 0;
    for (JsonNode result : results) {
      try {
        String address = requiredText(result, "url");
        if (address.contains(key)) {
          skipped++;
          continue;
        }
        var date = result.path("date");
        hits.add(new SearchHit(FetchRequest.of(address).url(),
            redact(requiredText(result, "title"), key),
            redact(requiredText(result, "snippet"), key),
            date.isString() ? redact(date.asString(), key) : null));
      } catch (IllegalArgumentException | WebProviderException invalidResult) {
        skipped++;
      }
      if (hits.size() == count) {
        break;
      }
    }
    if (hits.isEmpty() && skipped > 0) {
      throw failure(Kind.PROTOCOL);
    }
    return new SearchResults(hits, skipped);
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      throw failure(Kind.PROTOCOL);
    }
    return value.asString();
  }

  private static String redact(String value, String key) {
    return value.replace(key, "[已脱敏]");
  }

  private static Kind statusKind(int status) {
    return switch (status) {
      case 400, 413, 422 -> Kind.INVALID_REQUEST;
      case 401, 403 -> Kind.AUTH;
      case 402 -> Kind.PAYMENT_REQUIRED;
      case 404 -> Kind.UNAVAILABLE;
      case 408, 504 -> Kind.TIMEOUT;
      case 429 -> Kind.RATE_LIMITED;
      default -> Kind.UPSTREAM;
    };
  }

  private static WebProviderException failure(Kind kind) {
    return new WebProviderException(kind);
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final HttpResponse.BodySubscriber<byte[]> downstream =
        HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private Flow.Subscription subscription;
    private long received;

    private BoundedBody(int limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return downstream.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription upstream) {
      subscription = upstream;
      downstream.onSubscribe(upstream);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        received += buffer.remaining();
        if (received > limit) {
          subscription.cancel();
          downstream.onError(failure(Kind.PROTOCOL));
          return;
        }
      }
      downstream.onNext(buffers);
    }

    @Override
    public void onError(Throwable failure) {
      downstream.onError(failure);
    }

    @Override
    public void onComplete() {
      downstream.onComplete();
    }
  }
}
