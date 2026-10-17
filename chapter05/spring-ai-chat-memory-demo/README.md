# 5.2｜Spring AI 会话记忆

项目名称：`spring-ai-chat-memory-demo`  
项目路径：`java-ai-course-examples/chapter05/spring-ai-chat-memory-demo`

我用一个售后问答接口验证三件事：同一会话能接着聊，不同会话互不读取历史，历史消息超过窗口后会裁剪。这里实际使用 Spring AI 的 `ChatClient`、`MessageChatMemoryAdvisor` 和 `MessageWindowChatMemory`，模型请求会发往所选供应商。

## 环境和项目结构

使用 JDK 21、Maven 3.9+；父 POM 固定 Spring Boot 3.5.14、Spring AI 1.1.7。单项目 ZIP 包含根 POM、chapter05 的父 POM 和本项目。不要只取出项目子目录，否则 Maven 找不到父 POM。

| 文件（相对项目目录） | 用途 |
| --- | --- |
| `src/main/java/com/example/springaichatmemory/SpringAiChatMemoryDemo.java` | Spring Boot 启动入口 |
| `src/main/java/com/example/springaichatmemory/config/ChatMemoryConfiguration.java` | 创建 10 条消息的窗口，并注册记忆 Advisor |
| `src/main/java/com/example/springaichatmemory/config/ModelConfiguration.java` | 创建 DeepSeek、Ollama 或 ChatGPT 模型 |
| `src/main/java/com/example/springaichatmemory/web/MemoryChatController.java` | HTTP 请求、参数校验和会话 ID 传递 |
| `src/main/resources/application.yml` | 本机监听地址和端口 |
| `demo/MemoryChatSmokeClient.java` | 无第三方依赖的 Java HTTP 验证客户端 |
| `src/test/java/com/example/springaichatmemory/MemoryChatIntegrationTest.java` | HTTP 链路、记忆隔离与裁剪、模型适配器测试 |

## 配置模型

`APP_AI_DEFAULT_PROVIDER` 在启动时选择供应商，默认 `deepseek`，另支持 `ollama`、`chatgpt`。**它不是请求 JSON 参数**，切换配置后要重启服务。

| 供应商 | 环境变量 | 默认值 / 要求 |
| --- | --- | --- |
| DeepSeek | `DEEPSEEK_API_KEY` | 必填，不写进源码 |
| DeepSeek | `DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` | `https://api.deepseek.com`、`deepseek-v4-flash` |
| Ollama | `OLLAMA_BASE_URL`、`OLLAMA_MODEL` | `http://localhost:11434`、`qwen2.5:7b` |
| ChatGPT | `CHATGPT_API_KEY` | 必填，使用 OpenAI API 的密钥 |
| ChatGPT | `CHATGPT_BASE_URL`、`CHATGPT_MODEL` | `https://api.openai.com`、`gpt-4.1-mini` |

DeepSeek 和 ChatGPT 使用 OpenAI 兼容接口，base URL 结尾的 `/v1` 会规范化，避免重复拼接。Ollama 使用原生 `/api/chat`；需先启动 Ollama 并拉取所选模型。模型不可用时，用对应的 `*_MODEL` 环境变量指定账号或本地已有的模型。

## 启动并验证

以下命令都在解压后的 `java-ai-course-examples` 根目录执行。

1. 在运行进程的环境里设置 `DEEPSEEK_API_KEY`。IDE 运行配置和终端环境变量均可；不要把密钥填入 Java 文件。
2. 启动服务：`mvn -f chapter05/spring-ai-chat-memory-demo/pom.xml spring-boot:run`。
3. 在另一个终端运行：`java chapter05/spring-ai-chat-memory-demo/demo/MemoryChatSmokeClient.java`。

Java 客户端按顺序发送三次 `POST /api/memory-chat`：先在 east 会话说明“我负责华东区售后”，再在同一会话追问区域，最后在 south 会话问同一个问题。第二次应回答华东区，第三次应表示没有相关信息。真实模型的措辞不固定。

请求字段是 `conversationId` 和 `message`，响应字段是 `conversationId` 和 `answer`。会话 ID 不能为空且最多 128 字符，消息不能为空且最多 4000 字符；校验失败返回 HTTP 400。

端口默认 8080。若被占用，设置 `SERVER_PORT=18052` 后启动，并执行 `java chapter05/spring-ai-chat-memory-demo/demo/MemoryChatSmokeClient.java http://127.0.0.1:18052`。

也可以打包运行：`mvn -f chapter05/spring-ai-chat-memory-demo/pom.xml clean package`，再执行 `java -jar chapter05/spring-ai-chat-memory-demo/target/spring-ai-chat-memory-demo-0.0.1-SNAPSHOT.jar`。

## 记忆实际怎样工作

Controller 每次通过 `ChatMemory.CONVERSATION_ID` 指定当前会话，Advisor 把该会话历史放入模型请求并保存消息。不要在 Controller 里重复调用 `chatMemory.add`。

`maxMessages(10)` 表示消息条数，不是十轮对话。这里使用进程内存，重启就清空，也没有数据库、完整历史归档、用户鉴权和多实例共享。请求请按会话串行发送；同一会话的并发请求没有业务排队保证。会话 ID 用于分组，不能作为访问权限凭证。需要持久化时接着看 5.3。

## 自动化测试

执行 `mvn -f chapter05/spring-ai-chat-memory-demo/pom.xml test`。测试启动真实应用与本机模型 HTTP 桩，不需要真实 API Key，也不消耗模型额度。8 项测试覆盖历史续接、会话隔离、10 条窗口裁剪、非法输入、三种供应商的请求路径和响应解析、缺失密钥与错误供应商。

测试桩只在 `src/test` 中使用；正常启动不会切换成固定答案。三种适配器的离线测试不等同于三个供应商的在线可用性验证。

## 与旧包的区别

2026-10-16 版本替换了此前 `AdvisorStyleChatService`、`MessageWindowMemory`、`DeterministicModelClient` 的控制台模拟。原启动类名保留，但现在启动 HTTP 服务；正文中的配置类与 Controller 均可在本包找到。

参考：[Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)。本项目固定为 1.1.7，具体行为以该版本及本项目测试为准。
