package com.example.springaichatmemory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 5.2 的 HTTP 应用入口。启动后通过 POST /api/memory-chat 连续发送消息。
 * 记忆由真实 Spring AI Advisor 管理；应用重启后，内存中的会话会消失。
 */
@SpringBootApplication
public class SpringAiChatMemoryDemo {
    public static void main(String[] args) {
        SpringApplication.run(SpringAiChatMemoryDemo.class, args);
    }
}
