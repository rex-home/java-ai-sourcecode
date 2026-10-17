import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * JDK 21 自带 HTTP 客户端，不需要第三方依赖。
 * 先启动服务，再用 java demo/MemoryChatSmokeClient.java 运行这三个请求。
 */
public class MemoryChatSmokeClient {
    public static void main(String[] args) throws Exception {
        // 可在命令后传 http://127.0.0.1:18052，匹配自定义 SERVER_PORT。
        String baseUrl = args.length == 0 ? "http://127.0.0.1:8080" : args[0];
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        // 每次运行生成新 ID，防止上一次演示留下的记忆影响验证结果。
        String suffix = UUID.randomUUID().toString();
        String east = "east-" + suffix;
        String south = "south-" + suffix;
        send(client, baseUrl, east, "我负责华东区售后。请记住这个信息。");
        send(client, baseUrl, east, "我负责哪个区域？");
        send(client, baseUrl, south, "我负责哪个区域？");
        // 第二条应提到华东区，第三条应表示不知道；真实模型的措辞可能不同。
    }

    private static void send(HttpClient client, String baseUrl, String id, String message) throws Exception {
        // 此处仅使用程序生成的 UUID 和上面固定的文本；通用用户输入应由 JSON 序列化器处理。
        String json = "{\"conversationId\":\"" + id + "\",\"message\":\"" + message + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/memory-chat"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("接口返回 " + response.statusCode() + ": " + response.body());
        }
        System.out.println(response.body());
    }
}
