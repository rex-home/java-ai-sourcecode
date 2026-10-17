package com.example.springaichatmemory;

import com.example.springaichatmemory.config.ModelConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/**
 * 真正启动 HTTP 应用，经过 Controller -> ChatClient -> Advisor -> 模型 HTTP 适配器。
 * 只有外部模型服务由本机 HTTP 桩替代，记忆组件与模型适配器均使用真实 Spring AI 实现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemoryChatIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<JsonNode> REQUESTS = new ArrayList<>();
    private static final List<String> PATHS = new ArrayList<>();
    private static final HttpServer MODEL_SERVER = startModelServer();
    @Autowired TestRestTemplate http;
    @Autowired ChatMemory memory;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("APP_AI_DEFAULT_PROVIDER", () -> "deepseek");
        registry.add("DEEPSEEK_API_KEY", () -> "test-key");
        registry.add("DEEPSEEK_BASE_URL", MemoryChatIntegrationTest::baseUrl);
        registry.add("DEEPSEEK_MODEL", () -> "test-model");
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + MODEL_SERVER.getAddress().getPort();
    }

    private static HttpServer startModelServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                synchronized (REQUESTS) {
                    REQUESTS.add(body);
                    PATHS.add(exchange.getRequestURI().getPath());
                }
                // 用固定响应消除生成随机性；关键断言检查真实请求中的历史消息和隔离效果。
                String response = exchange.getRequestURI().getPath().equals("/api/chat")
                        ? "{\"model\":\"test-model\",\"created_at\":\"2026-01-01T00:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"收到\"},\"done\":true}"
                        : "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"收到\"},\"finish_reason\":\"stop\"}]}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @BeforeEach
    void reset() {
        memory.clear("east");
        memory.clear("south");
        synchronized (REQUESTS) { REQUESTS.clear(); PATHS.clear(); }
    }

    @AfterAll
    static void stopServer() { MODEL_SERVER.stop(0); }

    private void chat(String id, String message) {
        var response = http.postForEntity("/api/memory-chat",
                Map.of("conversationId", id, "message", message), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("conversationId").asText()).isEqualTo(id);
        assertThat(response.getBody().path("answer").asText()).isEqualTo("收到");
    }

    @Test
    void sameConversationSendsHistoryAndOtherConversationCannotSeeIt() {
        chat("east", "我负责华东区售后");
        chat("east", "我负责哪个区域");
        chat("south", "我负责哪个区域");
        // 第二次请求里应同时包含上一轮 user 和 assistant；另一会话不能包含这些消息。
        assertThat(REQUESTS.get(1).path("messages").toString()).contains("华东区", "收到");
        assertThat(REQUESTS.get(2).path("messages").toString()).doesNotContain("华东区", "收到");
        assertThat(memory.get("east")).hasSize(4);
        assertThat(memory.get("south")).hasSize(2);
        assertThat(memory.get("east").get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(memory.get("east").get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
    }

    @Test
    void windowRemovesOldMessagesAndRetainsRecentMessages() {
        for (int i = 0; i < 7; i++) chat("east", "业务编号-" + i);
        assertThat(memory.get("east")).hasSize(10);
        assertThat(memory.get("east").stream().map(m -> m.getText()).toList())
                .contains("业务编号-6").doesNotContain("业务编号-0", "业务编号-1");
        assertThat(REQUESTS.get(6).path("messages").toString()).doesNotContain("业务编号-0");
    }

    @Test
    void blankOrMissingInputIsRejectedBeforeCallingModel() {
        List<Map<String, String>> invalid = List.of(
                Map.of("conversationId", " ", "message", "你好"),
                Map.of("message", "你好"),
                Map.of("conversationId", "east", "message", ""),
                Map.of("conversationId", "east"));
        for (var body : invalid) {
            assertThat(http.postForEntity("/api/memory-chat", body, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "chatgpt", "ollama"})
    void eachProviderUsesItsRealHttpAdapter(String provider) {
        Map<String, String> config = new HashMap<>();
        config.put("APP_AI_DEFAULT_PROVIDER", provider);
        String prefix = provider.toUpperCase(java.util.Locale.ROOT);
        // OpenAI 兼容地址故意带 /v1/，验证不会重复拼接；Ollama 使用原生路径。
        config.put(prefix + "_BASE_URL", baseUrl() + (provider.equals("ollama") ? "" : "/v1/"));
        config.put(prefix + "_MODEL", "test-model");
        config.put(prefix + "_API_KEY", "test-key");
        assertThat(ModelConfiguration.createModel(config::get).call("你好")).isEqualTo("收到");
        assertThat(PATHS).containsExactly(provider.equals("ollama") ? "/api/chat" : "/v1/chat/completions");
        assertThat(REQUESTS.getFirst().path("model").asText()).isEqualTo("test-model");
    }

    @Test
    void missingKeyFailsClearlyAndDefaultProviderIsDeepseek() {
        assertThatThrownBy(() -> ModelConfiguration.createModel(key -> null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void invalidProviderFailsClearly() {
        assertThatThrownBy(() -> ModelConfiguration.createModel(
                Map.of("APP_AI_DEFAULT_PROVIDER", "unknown")::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("仅支持");
    }
}
