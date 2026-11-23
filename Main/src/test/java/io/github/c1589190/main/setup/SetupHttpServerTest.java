// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.main.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.c1589190.agentlib.llm.LlmRequest;
import io.github.c1589190.agentlib.llm.LlmRouteLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SetupHttpServer} 端到端（真 HTTP + 真 SSE stub 后端，{@code 设计-配置引导.md} 判据 4）： 合法 provider ⇒ 探测过 →
 * 200 → status 转 configured → 路由读得回 → 路由器热切换后 chat 走 stub； 探测失败 ⇒ 400 且<b>不写盘</b>；坏名/方法不符各一钉。
 */
@Timeout(60)
class SetupHttpServerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private HttpServer stub;
  private SetupHttpServer server;
  private SetupLlmRouter router;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (stub != null) {
      stub.stop(0);
    }
  }

  /** 一个会回答 SSE 的 OpenAI 兼容 stub（一帧内容 + usage + DONE）。 */
  private int startStub() throws IOException {
    return startStub(request -> false);
  }

  private int startStub(Predicate<JsonNode> rejects) throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            if (rejects.test(JSON.readTree(exchange.getRequestBody()))) {
              exchange.sendResponseHeaders(422, -1);
              exchange.close();
              return;
            }
            byte[] body =
                ("data:"
                        + " {\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"pong\"}}]}\n\n"
                        + "data:"
                        + " {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n"
                        + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          } catch (IOException clientHungUp) {
            // 客户端读完 [DONE] 即停，属预期
          }
        });
    stub.start();
    return stub.getAddress().getPort();
  }

  private void startServer() {
    router = SetupLlmRouter.unconfigured();
    server = SetupHttpServer.start(0, tempDir, router);
  }

  private HttpResponse<String> post(String json) throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/setup/llm"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> getStatus() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/api/setup/status"))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void validProviderProbesWritesSwapsAndReportsConfigured() throws Exception {
    int stubPort = startStub();
    startServer();

    assertThat(getStatus().body()).contains("\"configured\":false").contains("unconfigured");

    HttpResponse<String> response =
        post(
            "{\"name\":\"default\",\"baseUrl\":\"http://127.0.0.1:"
                + stubPort
                + "/v1\",\"model\":\"stub-model\",\"apiKey\":\"sk-test\","
                + "\"maxContext\":131072,\"maxOutput\":8192}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"ok\":true").contains("stub-model");
    assertThat(getStatus().body()).contains("\"configured\":true");
    assertThat(router.model()).isEqualTo("stub-model");
    // 路由读回（写盘证据）+ 密钥作为 Bearer 到达 provider
    assertThat(
            LlmRouteLoader.load(new io.github.c1589190.agentlib.config.FileConfigStore(tempDir))
                .model())
        .isEqualTo("stub-model");
    assertThat(router.chat(LlmRequest.ofMessages(java.util.List.of())).textPart()).contains("pong");
  }

  @Test
  void unreachableProviderReturns400AndWritesNothing() throws Exception {
    startServer();

    HttpResponse<String> response =
        post(
            "{\"baseUrl\":\"http://127.0.0.1:1\",\"model\":\"m\",\"apiKey\":\"\","
                + "\"maxContext\":131072,\"maxOutput\":8192}");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("探测失败");
    assertThat(router.configured()).isFalse();
    assertThat(new io.github.c1589190.agentlib.config.FileConfigStore(tempDir).get("llm", "routes"))
        .isEmpty();
  }

  @Test
  void existingRouteEffortRejectedByProviderLeavesConfigurationAndRouterUntouched()
      throws Exception {
    Path configFile = tempDir.resolve("config.json");
    String existing =
        """
        {"llm":{"routes":{"default":{"baseUrl":"https://old.example/v1","model":"old-model",
            "reasoningEffort":"max","systemPrompt":"启动提示词样本"}}}}
        """;
    Files.writeString(configFile, existing);
    var captured = new AtomicReference<JsonNode>();
    int stubPort =
        startStub(
            request -> {
              captured.set(request);
              return "max".equals(request.path("reasoning_effort").asText());
            });
    startServer();

    HttpResponse<String> response =
        post(
            """
            {"baseUrl":"http://127.0.0.1:%d/v1","model":"stub-model","maxContext":131072,"maxOutput":8192}
            """
                .formatted(stubPort));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("探测失败");
    JsonNode request = captured.get();
    assertThat(request.path("reasoning_effort").asText()).isEqualTo("max");
    JsonNode messages = request.path("messages");
    assertThat(messages.size()).isEqualTo(1);
    assertThat(messages.path(0).path("role").asText()).isEqualTo("user");
    assertThat(messages.path(0).path("content").asText()).isEqualTo("ping");
    assertThat(Files.readString(configFile)).isEqualTo(existing);
    assertThat(router.configured()).isFalse();
  }

  @Test
  void invalidCapacityFieldsAre400AndWriteNothing() throws Exception {
    startServer();
    List<String> invalidBodies =
        List.of(
            "{\"baseUrl\":\"http://127.0.0.1:1\",\"model\":\"m\"}",
            "{\"baseUrl\":\"http://127.0.0.1:1\",\"model\":\"m\",\"maxContext\":131072}",
            "{\"baseUrl\":\"http://127.0.0.1:1\",\"model\":\"m\",\"maxContext\":0,"
                + "\"maxOutput\":8192}",
            "{\"baseUrl\":\"http://127.0.0.1:1\",\"model\":\"m\",\"maxContext\":\"131072\","
                + "\"maxOutput\":8192}",
            "{\"baseUrl\":\"http://127.0.0.1:1\",\"model\":\"m\",\"maxContext\":2147483648,"
                + "\"maxOutput\":8192}");

    for (String body : invalidBodies) {
      HttpResponse<String> response = post(body);
      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(response.body())
          .as(body)
          .contains("正整数")
          .satisfies(text -> assertThat(text).containsAnyOf("maxContext", "maxOutput"));
    }
    assertThat(router.configured()).isFalse();
    assertThat(new io.github.c1589190.agentlib.config.FileConfigStore(tempDir).get("llm", "routes"))
        .isEmpty();
  }

  @Test
  void badRouteNameIsRejectedBeforeProbe() throws Exception {
    int stubPort = startStub();
    startServer();

    HttpResponse<String> response =
        post(
            "{\"name\":\"bad name\",\"baseUrl\":\"http://127.0.0.1:"
                + stubPort
                + "/v1\",\"model\":\"m\"}");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("name 非法");
    assertThat(router.configured()).isFalse();
  }

  @Test
  void wrongMethodGets405() throws Exception {
    startServer();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/api/setup/llm"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(405);
  }
}
