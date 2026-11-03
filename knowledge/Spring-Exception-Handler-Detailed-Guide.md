# Spring @RestControllerAdvice + @ExceptionHandler 详解

> 配套代码：`chapter10/enterprise-ops-agent` → `web/OpsApiExceptionHandler.java`

## 示例代码

```java
@RestControllerAdvice
public class OpsApiExceptionHandler {

    @ExceptionHandler(IncidentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(IncidentNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fieldError -> fieldError.getDefaultMessage())
            .orElse("请求参数不合法");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "timestamp", Instant.now(),
            "status", status.value(),
            "error", status.getReasonPhrase(),
            "message", message
        ));
    }
}
```

三条异常 → HTTP 响应的映射规则：

| 异常类型 | HTTP 状态码 | 含义 |
|---------|-----------|------|
| `IncidentNotFoundException` | 404 Not Found | 排障事件不存在 |
| `IllegalStateException` | 409 Conflict | 业务冲突（如状态不允许当前操作） |
| `MethodArgumentNotValidException` | 400 Bad Request | 请求参数校验失败 |

---

## 一、注解含义

### @RestControllerAdvice

拆开来看：

```
@ControllerAdvice  →  给所有 Controller "加的建议/增强"
@ResponseBody      →  方法返回值直接写入 HTTP 响应体（而非跳转到视图）
```

`@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody` 的组合快捷方式。

| 注解 | 返回值行为 | 典型场景 |
|------|-----------|---------|
| `@ControllerAdvice` | 默认走视图解析（返回页面名） | 传统服务端渲染 |
| `@RestControllerAdvice` | 自动 JSON 序列化写入响应体 | REST API |

还可以带参数缩小作用范围（默认作用于所有 Controller）：

```java
// 只拦截指定包下的 Controller
@RestControllerAdvice(basePackages = "com.example.enterpriseopsagent.web")

// 只拦截带特定注解的 Controller
@RestControllerAdvice(annotations = IncidentApi.class)

// 只拦截指定类型的 Controller
@RestControllerAdvice(assignableTypes = {IncidentController.class, AgentController.class})
```

### @ExceptionHandler

标记方法为**异常处理方法**，括号里指定它能处理哪些类型的异常（支持多个）：

```java
// 处理一种异常
@ExceptionHandler(IncidentNotFoundException.class)

// 处理多种异常
@ExceptionHandler({IncidentNotFoundException.class, IncidentExpiredException.class})
```

方法参数必须是 `Throwable` 的子类（即某种异常类型），返回值可以是：

| 返回类型 | 含义 |
|---------|------|
| `ResponseEntity<?>` | 完全控制响应状态码、头、体（最常用） |
| `@ResponseBody` 标注的任意对象 | 直接作为 JSON 响应体，状态码默认 200 |
| `String` | 视图名（配合 `@ControllerAdvice` 使用） |
| `ModelAndView` | 老版本视图渲染方式 |

---

## 二、为什么要用全局异常处理

没有全局异常处理时，每个 Controller 方法都要写 try-catch：

```java
// ❌ 不好：每个方法都要重复写
@GetMapping("/incidents/{id}")
public DiagnosisIncident getIncident(@PathVariable String id) {
    try {
        return service.getIncident(id);
    } catch (IncidentNotFoundException e) {
        return ResponseEntity.notFound().build();
    } catch (IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
```

有了 `@RestControllerAdvice`，Controller 代码干净纯粹：

```java
// ✅ 好：只管业务逻辑
@GetMapping("/incidents/{id}")
public DiagnosisIncident getIncident(@PathVariable String id) {
    return service.getIncident(id);
}
```

---

## 三、底层实现原理

整个流程围绕 `DispatcherServlet` 和 `HandlerExceptionResolver` 体系展开。

### 第一步：启动时扫描注册

Spring 容器启动时扫描所有标注了 `@ControllerAdvice` 的 Bean。

对于每个 `@ControllerAdvice` Bean，Spring 解析内部所有 `@ExceptionHandler` 标注的方法，建立一张**异常类型 → 处理方法**的映射表：

```
Map<Class<? extends Throwable>, Method>
├── IncidentNotFoundException.class  → handleNotFound 方法
├── IllegalStateException.class       → handleConflict 方法
└── MethodArgumentNotValidException  → handleValidation 方法
```

这张映射表由内部类 `ExceptionHandlerMethodResolver` 管理。

### 第二步：请求处理中抛异常

Spring MVC 处理请求的主循环在 `DispatcherServlet#doDispatch()` 中：

```
HttpServletRequest 进来
    ↓
找到 Handler（Controller 方法）
    ↓
HandlerAdapter 调用 Controller 方法
    ↓
抛出 IncidentNotFoundException
    ↓
异常冒泡回 DispatcherServlet.doDispatch() 的 catch 块
```

### 第三步：异常解析器链匹配

Spring 将异常交给**责任链**——`HandlerExceptionResolver` 列表，按顺序询问："你能处理这个异常吗？"

Spring Boot 默认注册的解析器顺序：

```
1. ExceptionHandlerExceptionResolver  ← @ExceptionHandler 在这里被匹配
2. ResponseStatusExceptionResolver     ← 处理 @ResponseStatus 注解的异常
3. DefaultHandlerExceptionResolver     ← 处理 Spring 内置标准异常（如 NoHandlerFoundException）
```

`ExceptionHandlerExceptionResolver` 的匹配逻辑：

```
拿到异常对象 e
    ↓
遍历所有 ControllerAdvice Bean 中的 ExceptionHandlerMethodResolver
    ↓
对于每个 Resolver：
    查找 e 的类型（含父类）在映射表中有没有对应的 Method
    ↓
有 → 反射调用那个方法，拿到返回值
没有 → 返回 null，继续问下一个 Resolver
```

### 第四步：匹配规则细节

#### 规则一：找"最具体"的类型（最小距离优先）

```java
// 假设继承关系：RuntimeException ← IncidentNotFoundException ← IncidentExpiredException

@ExceptionHandler(RuntimeException.class)            // 距离 = 2
@ExceptionHandler(IncidentNotFoundException.class)  // 距离 = 1
```

抛出 `IncidentExpiredException` 时，Spring 选距离 1 的那个处理器。

#### 规则二：优先匹配"当前 Controller 自己的"处理器

如果某个 Controller 类内部也定义了 `@ExceptionHandler`，优先级高于全局 `@ControllerAdvice`。Spring 会先查 Controller 自身，再查全局 Advice。

### 第五步：把返回值转成 HTTP 响应

匹配到方法后，Spring 通过反射调用，拿到 `ResponseEntity<Map<String, Object>>`，然后：

```
HttpMessageConverter（默认 Jackson）将 Map 序列化为 JSON
    ↓
写入 HttpServletResponse
    ↓
设置状态码（如 404）
```

### 完整调用链

```
Controller 抛异常
    ↓
DispatcherServlet.doDispatch() catch
    ↓
ExceptionHandlerExceptionResolver.resolveException()
    ↓
ExceptionHandlerMethodResolver.getMethodByExceptionType() ← 查表匹配
    ↓
反射调用 handleNotFound()
    ↓
HttpMessageConverter 序列化 ResponseEntity.body → JSON
    ↓
写入 HttpServletResponse，状态码 404
```

---

## 四、整体流程图

```
┌──────────────────────────────────────────────────────────┐
│  启动阶段                                                 │
│                                                          │
│  Spring 容器扫描                                         │
│  ├── @RestControllerAdvice Bean 被发现                    │
│  │   └── 内部 @ExceptionHandler 方法被解析               │
│  │       └── 建立 ExceptionType → Method 映射表          │
│  └── 注册 ExceptionHandlerExceptionResolver 到解析器链   │
└──────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────┐
│  运行阶段                                                 │
│                                                          │
│  HTTP 请求 → DispatcherServlet                           │
│      ↓                                                   │
│  Controller 方法执行时抛异常                              │
│      ↓                                                   │
│  异常冒泡回 DispatcherServlet                             │
│      ↓                                                   │
│  遍历 HandlerExceptionResolver 链                         │
│      ↓                                                   │
│  ExceptionHandlerExceptionResolver 匹配成功              │
│      ↓                                                   │
│  反射调用对应的 handleXxx() 方法                          │
│      ↓                                                   │
│  ResponseEntity → 序列化 → HTTP JSON 响应                │
└──────────────────────────────────────────────────────────┘
```

---

## 五、自定义异常类的设计（配套知识点）

全局异常处理常常配合**自定义业务异常**使用，如 `IncidentNotFoundException`：

```java
public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException(String incidentId) {
        super("排障事件不存在：" + incidentId);
    }
}
```

### 为什么要自定义异常？

| 价值 | 说明 |
|------|------|
| **语义清晰** | 类名本身就是自描述文档，一眼知道是什么业务场景出错 |
| **精确捕获** | 可以和其他异常走不同的处理分支（如 404 vs 500） |
| **可扩展** | 未来可以携带结构化字段（tenantId、userId 等） |
| **分层隔离** | 业务层不依赖 Web 层异常类，业务模块可以独立复用 |

### 什么时候不需要自定义异常？

异常只在一个地方抛出、处理逻辑和其他 `RuntimeException` 完全一样、且永远不会携带额外数据时，用通用 `RuntimeException` 即可。
