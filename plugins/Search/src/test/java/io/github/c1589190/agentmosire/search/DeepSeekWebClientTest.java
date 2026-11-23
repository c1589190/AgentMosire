// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.agentmosire.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 用回环服务控制真实联网接口无法稳定触发的协议分支。 */
@Timeout(10)
class DeepSeekWebClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "local-fixture-key";
  private static final String NEXT_KEY = "local-fixture-rotated";
  private static final URI PAGE = URI.create("https://docs.example.com/a");
  private static final SearchRequest QUERY = new SearchRequest("网页协议", 2);
  private static final String EMPTY_RESULTS = "{\"results\":[]}";
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
  private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
  private final AtomicReference<String> key = new AtomicReference<>(KEY);
  private HttpServer server;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void searchAndFetchUseTheirWireSchemasAndReadTheCurrentKey() throws Exception {
    replies.add(
        Reply.json(
            200,
            """
        {"results":[{"url":"https://docs.example.com/a","title":"标题","snippet":"摘要","date":"2026-11-22"}]}
        """));
    replies.add(
        Reply.json(
            200,
            """
        {"title":"标题","content":"# 正文 local-fixture-rotated"}
        """));
    try (var client = client(REQUEST_TIMEOUT, 4096)) {
      assertEquals(List.of(new SearchHit(PAGE, "标题", "摘要", "2026-11-22")), client.search(QUERY));
      key.set(NEXT_KEY);
      var page = client.fetch(new FetchRequest(PAGE));
      assertEquals("标题", page.title());
      assertEquals("# 正文 [已脱敏]", page.content());
    }
    assertEquals(
        List.of(
            new Request(
                "POST",
                "/v1/tools/search",
                "Bearer " + KEY,
                "application/json",
                JSON.readTree("{\"query\":\"网页协议\",\"count\":2}")),
            new Request(
                "POST",
                "/v1/tools/fetch",
                "Bearer " + NEXT_KEY,
                "application/json",
                JSON.readTree("{\"url\":\"https://docs.example.com/a\"}"))),
        List.copyOf(requests));
  }

  @Test
  void exactlyOneRetryAllowsEmptySuccessAndFailureDoesNotRetryAgain() {
    replies.add(Reply.json(429, "{}"));
    replies.add(Reply.json(200, EMPTY_RESULTS));
    replies.add(Reply.json(503, "{}"));
    replies.add(Reply.json(503, "{}"));
    replies.add(Reply.json(200, EMPTY_RESULTS));
    try (var client = client(REQUEST_TIMEOUT, 4096)) {
      assertEquals(List.of(), client.search(QUERY));
      assertEquals(2, requests.size());
      assertEquals(
          WebProviderException.Kind.UPSTREAM,
          assertThrows(WebProviderException.class, () -> client.search(QUERY)).kind());
      assertEquals(4, requests.size());
      assertEquals(1, replies.size());
    }
  }

  @Test
  void redirectsAndAuthenticationFailuresDoNotLeakOrResendCredentials() {
    replies.add(new Reply(307, "{}", "/redirected", null));
    replies.add(Reply.json(401, "{\"error\":\"" + KEY + "\"}"));
    try (var client = client(REQUEST_TIMEOUT, 4096)) {
      assertEquals(
          WebProviderException.Kind.UPSTREAM,
          assertThrows(WebProviderException.class, () -> client.search(QUERY)).kind());
      assertEquals(List.of("/v1/tools/search"), requests.stream().map(Request::path).toList());
      var auth = assertThrows(WebProviderException.class, () -> client.search(QUERY));
      assertEquals(WebProviderException.Kind.AUTH, auth.kind());
      assertFalse(auth.toString().contains(KEY));
      assertEquals(2, requests.size());
    }
  }

  @Test
  void oversizedResponseIsRejectedWithoutBeingReturned() {
    replies.add(Reply.json(200, EMPTY_RESULTS + " ".repeat(4096)));
    try (var client = client(REQUEST_TIMEOUT, 64)) {
      assertEquals(
          WebProviderException.Kind.PROTOCOL,
          assertThrows(WebProviderException.class, () -> client.search(QUERY)).kind());
      assertEquals(1, requests.size());
    }
  }

  @Test
  void deadlineAlsoCoversAStalledResponseBody() {
    CountDownLatch release = new CountDownLatch(1);
    replies.add(new Reply(200, EMPTY_RESULTS, null, release));
    try (var client = client(Duration.ofSeconds(2), 4096)) {
      assertEquals(
          WebProviderException.Kind.TIMEOUT,
          assertThrows(WebProviderException.class, () -> client.search(QUERY)).kind());
      assertEquals(1, requests.size());
    } finally {
      release.countDown();
    }
  }

  private DeepSeekWebClient client(Duration timeout, int byteLimit) {
    return DeepSeekWebClient.forLoopback(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
        () -> Optional.of(key.get()),
        timeout,
        byteLimit);
  }

  private void respond(HttpExchange exchange) throws IOException {
    try (exchange) {
      var headers = exchange.getRequestHeaders();
      requests.add(
          new Request(
              exchange.getRequestMethod(),
              exchange.getRequestURI().getPath(),
              headers.getFirst("Authorization"),
              headers.getFirst("Content-Type"),
              JSON.readTree(exchange.getRequestBody())));
      var reply = replies.remove();
      if (reply.location() != null) {
        exchange.getResponseHeaders().set("Location", reply.location());
      }
      byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), bytes.length);
      if (reply.release() != null) {
        try {
          reply.release().await(6, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      exchange.getResponseBody().write(bytes);
    }
  }

  private record Request(
      String method, String path, String authorization, String contentType, JsonNode body) {}

  private record Reply(int status, String body, String location, CountDownLatch release) {

    static Reply json(int status, String body) {
      return new Reply(status, body, null, null);
    }
  }
}
