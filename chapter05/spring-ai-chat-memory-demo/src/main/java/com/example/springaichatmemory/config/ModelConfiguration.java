package com.example.springaichatmemory.config;

import java.util.Locale;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 只创建一个模型 Bean，避免三个供应商的自动配置相互干扰。密钥只从外部配置读取。 */
@Configuration
public class ModelConfiguration {
    @Bean
    public ChatModel chatModel(Environment environment) {
        // Environment 同时支持系统环境变量和 Spring 启动参数，便于本机运行与测试。
        return createModel(environment::getProperty);
    }

    public static ChatModel createModel(Function<String, String> config) {
        // 供应商在启动时选择。HTTP 请求不负责切换模型，切换后需要重启应用。
        String provider = value(config, "APP_AI_DEFAULT_PROVIDER", "deepseek").toLowerCase(Locale.ROOT);
        if (provider.equals("ollama")) {
            // Ollama 使用原生 /api/chat 协议，本机模型无需云端 API Key。
            return OllamaChatModel.builder()
                    .ollamaApi(OllamaApi.builder()
                            .baseUrl(value(config, "OLLAMA_BASE_URL", "http://localhost:11434")).build())
                    .defaultOptions(OllamaChatOptions.builder()
                            .model(value(config, "OLLAMA_MODEL", "qwen2.5:7b")).temperature(0.0).build())
                    .build();
        }
        if (!provider.equals("deepseek") && !provider.equals("chatgpt")) {
            throw new IllegalArgumentException("APP_AI_DEFAULT_PROVIDER 仅支持 deepseek、ollama、chatgpt");
        }
        String prefix = provider.equals("deepseek") ? "DEEPSEEK" : "CHATGPT";
        String apiKey = value(config, prefix + "_API_KEY", "");
        // 缺少密钥时明确失败，不悄悄返回模拟答案，也不把密钥写入日志。
        if (apiKey.isBlank()) {
            throw new IllegalStateException("请设置环境变量 " + prefix + "_API_KEY");
        }
        String baseUrl = value(config, prefix + "_BASE_URL",
                provider.equals("deepseek") ? "https://api.deepseek.com" : "https://api.openai.com");
        // OpenAiApi 默认追加 /v1/chat/completions，去掉尾部 /v1 防止路径重复。
        baseUrl = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(value(config, prefix + "_MODEL",
                                provider.equals("deepseek") ? "deepseek-v4-flash" : "gpt-4.1-mini"))
                        .temperature(0.0).build())
                .build();
    }

    private static String value(Function<String, String> config, String key, String fallback) {
        String raw = config.apply(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }
}
