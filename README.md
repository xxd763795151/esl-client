# FreeSWITCH ESL Client for Java

基于 Netty 4.2 的 FreeSWITCH Event Socket（ESL）客户端：inbound 客户端、outbound 服务端、事件、拨号方案应用（APP）调用、自动重连、平滑关闭、Micrometer 指标、Spring Boot Starter。

- Java 17+，核心库只依赖 `netty` 和 `slf4j-api`
- 全异步 API（`CompletableFuture`），回调不在 Netty I/O 线程上执行；在 I/O 线程上阻塞会被检测并直接报错，而不是死锁
- 2.0.0 是完全重写的版本，不兼容 1.x，迁移见 [MIGRATION.md](MIGRATION.md)

## 坐标

```xml
<dependency>
    <groupId>io.github.xxd763795151</groupId>
    <artifactId>esl-client</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- Spring Boot 4.x -->
<dependency>
    <groupId>io.github.xxd763795151</groupId>
    <artifactId>esl-client-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

| 模块 | 说明 |
|---|---|
| `esl-client` | 核心库 |
| `esl-client-spring-boot-starter` | 自动配置、生命周期、健康检查、`@EslEventListener` |
| `esl-client-examples` | 可运行示例（不发布） |

## Inbound：连接 FreeSWITCH

```java
InboundClient client = InboundClient.builder()
        .address("10.0.0.10", 8021)            // 可以配置多个地址，重连时轮换（故障转移）
        .password("ClueCon")                   // 或 .user("1000@domain") 走 userauth
        .events("CHANNEL_ANSWER", "CHANNEL_HANGUP_COMPLETE")
        .reconnectPolicy(ReconnectPolicy.exponential().maxDelay(Duration.ofSeconds(30)).build())
        .build();

client.addEventListener(EventSelector.named("CHANNEL_ANSWER"),
        event -> log.info("answered {}", event.uniqueId()));

client.connect().join();                                      // 认证完成并且初始订阅生效后才完成

String status = client.api("status").join().body();           // api：同步执行
BackgroundJobResult job = client.bgapi("originate", "user/1000 &park").join();  // bgapi：等待 BACKGROUND_JOB

client.close();                                               // 平滑关闭，见下文
```

## Outbound：在拨号方案中把呼叫交给程序

```xml
<action application="socket" data="127.0.0.1:8084 async full"/>
```

```java
OutboundServer server = OutboundServer.builder()
        .port(8084)
        .linger(Duration.ofSeconds(5))
        .handler(session -> {                       // 在独立的工作线程上运行，可以按顺序同步编写脚本
            CallChannel call = session.channel();
            call.answer().join();
            String digits = call.playAndGetDigits(PlayAndGetDigits.builder("ivr/menu.wav").digits(1, 4).build()).join();
            call.playback("ivr/thank_you.wav").join();
            call.hangup().join();
        })
        .build();
server.start().join();
...
server.shutdownGracefully(Duration.ofSeconds(30)).join();   // 停止接入新呼叫，等待进行中的呼叫结束
```

`OutboundSessionHandler` 还提供 `onEvent`（按顺序推送本通话的事件，与 `onConnect` 并行）、`onDisconnect`（所有事件之后调用）和 `onServerShutdown`。

## APP（拨号方案应用）支持

旧版只能发 `api`，2.0 把 `sendmsg execute` 做成了一等公民，inbound 和 outbound 都可用：

- 每次执行都带客户端生成的 `Event-UUID`。FreeSWITCH 会在 `CHANNEL_EXECUTE_COMPLETE` 里通过 `Application-UUID` 原样带回，future 只在**应用真正执行完**时完成。所以 `play_and_get_digits`、`read` 收集到的按键可以直接从完成事件里读取，不存在旧版那种"应用还没执行完就去取变量"的问题。
- 通话先挂断时，future 以 `ChannelHangupException` 失败。
- 支持 `event-lock`、`async`、`loops`。参数过长或包含多行时自动改用 body 发送（`content-length`）。
- 所需事件会自动订阅：inbound 订阅 `CHANNEL_EXECUTE_COMPLETE`/`CHANNEL_HANGUP_COMPLETE`，outbound 使用 `myevents`。

```java
CallChannel call = client.channel(uuid);                // inbound 也能控制任意通道
call.execute(Execute.builder("playback").argument("a.wav").eventLock(true).build()).join();
call.execute(Execute.builder("playback").argument("b.wav").fireAndForget().build()).join(); // 只等 +OK
call.getVariable("caller_id_number").join();            // uuid_getvar
call.breakMedia(true).join();                            // uuid_break
```

`CallChannel` 内置 answer、hangup、bridge、transfer、park、playback、speak、playAndGetDigits、read、record、recordSession、sendDtmf、set/export/unset、sleep、conference、uuid_kill、uuid_exists 等。其它应用用 `execute(app, arg)` 调用。

协议命令全部建模为 `sealed interface EslCommand`：`auth`、`userauth`、`api`、`bgapi`（客户端指定 `Job-UUID`）、`event plain|json|xml`、`nixevent`、`noevents`、`filter`/`filter delete`、`myevents`、`divert_events`、`sendmsg`（execute/hangup/nomedia/unicast）、`sendevent`、`log`/`nolog`、`linger`/`nolinger`、`connect`、`resume`、`getvar`、`exit`，以及兜底的 `Raw`。

## 平滑关闭

`closeGracefully(timeout)` 分阶段执行，每个阶段都有超时兜底，保证在 `timeout` 内一定结束：

1. 进入 `CLOSING`，拒绝新命令；
2. 等待已发出命令的回复（最多用一半时间）；
3. 发送 `exit`，等待 FreeSWITCH 回 `+OK bye` 并主动断开；
4. `shutdownOutput()` 半关闭（发 FIN），等待对端关闭；
5. **超时仍未关闭时，设置 `SO_LINGER=0` 再 close，直接发 RST 拆除连接**，不会在 `CLOSE_WAIT`/`FIN_WAIT2` 里残留；
6. 让未完成的 future 失败，等待事件分发队列排空，释放自有线程（全部是守护线程）。

`close()` 等价于用默认超时调用 `closeGracefully` 并等待其完成。在 I/O 或工作线程上调用时不会等待，避免自己等自己。Outbound 服务端关闭时先停止监听，再通知会话，等待呼叫自然结束（最多 2/3 超时），最后强制关闭剩余会话。

**假死检测**：inbound 读空闲（默认 60s）后发送探测命令（默认 `api version`）。超时则判定连接已死，RST 后触发重连。有命令在途时不探测（FreeSWITCH 逐个执行命令，慢 `api` 会阻塞 socket），由命令自身的超时兜底。

## 重连与状态恢复

```java
ReconnectPolicy.none();
ReconnectPolicy.fixed(Duration.ofSeconds(5), 10);           // 固定间隔，最多 10 次
ReconnectPolicy.exponential()                               // 指数退避 + 抖动（默认）
        .initialDelay(Duration.ofSeconds(1)).maxDelay(Duration.ofSeconds(30))
        .multiplier(2).jitter(0.2).maxAttempts(0).build();
(attempt, cause) -> ...                                     // 自定义 lambda
policy.withMaxAttempts(5);  policy.retryingAllFailures();   // 组合
```

- 密码错误和 ACL 拒绝（`text/rude-rejection`）默认不重试，可以用 `retryingAllFailures()` 改变。
- 恢复策略 `RecoveryStrategy`：默认 `replayState()` 按序重放订阅（含格式与 CUSTOM 子类）、filter 和 log 级别。也可以用 `none()`，或用 `andThen(ctx -> ...)` 追加自定义步骤。
- 断线期间的新命令：`FAIL_FAST`（默认），或 `QUEUE_UNTIL_READY`（有界队列，每条命令仍受超时约束）。
- `retryInitialConnect(true)` 时，首次连接失败也会按策略重试。

## 线程模型与背压

- 解码在 Netty I/O 线程完成，监听器在工作线程执行。工作线程默认是可伸缩的守护线程池，也可以换成你自己的 executor 或虚拟线程（Java 21+，`EslResources.builder().virtualThreads(true)`）。
- `DispatchMode`：`PER_CHANNEL_ORDERED`（默认，同一通话按 `Unique-ID` 严格有序，不同通话并行）、`SEQUENTIAL`、`UNORDERED`。
- 背压：积压超过高水位时暂停读 socket（`PAUSE_READ`，默认），低于低水位时恢复；也可以改为丢弃并计数（`DROP`）。
- 多个客户端可以共享一套 `EslResources`。传输层自动选择 epoll/kqueue（需要在 classpath 中加入对应 native 包），否则使用 NIO。

## 指标（Micrometer，可选）

```java
InboundClient.builder().metrics(new MicrometerEslMetrics(meterRegistry, "fs1"))
```

`esl.commands`（timer，tag：command/outcome）、`esl.events.received`、`esl.events.dropped`、`esl.listener.errors`、`esl.reconnect.attempts`、`esl.state.transitions`、`esl.sessions.opened/closed`，以及 gauge：`esl.connected`、`esl.pending.commands|jobs|executes`、`esl.dispatch.backlog`、`esl.sessions.active`。客户端关闭时自动移除这些 meter。

## Spring Boot

```yaml
esl:
  inbound:
    enabled: true
    addresses: [10.0.0.10:8021, 10.0.0.11:8021]
    password: ClueCon
    events: [CHANNEL_ANSWER, CHANNEL_HANGUP_COMPLETE]
    reconnect:
      strategy: exponential     # none | fixed | exponential
      max-delay: 30s
    disconnected-command-policy: queue-until-ready
  outbound:                     # 存在 OutboundSessionHandler bean 时自动启动
    port: 8084
    shutdown-timeout: 30s
spring:
  lifecycle:
    timeout-per-shutdown-phase: 40s
```

```java
@EslEventListener("CHANNEL_HANGUP_COMPLETE")
void onHangup(EslEvent event) { ... }
```

- 生命周期：inbound 客户端比 Web 服务器先启动、后关闭；outbound 服务端与 Web 服务器同阶段启停。
- 启动时默认最多等 10s 建立连接（`startup-wait`），连不上就在后台继续重试；设置 `fail-fast: true` 时直接让启动失败。
- 有 Micrometer 时自动接入指标；有 `spring-boot-health` 时提供 `eslInbound`/`eslOutbound` 健康检查。
- 用 `InboundClientCustomizer` / `OutboundServerCustomizer` 定制属性覆盖不到的部分。IDE 可以补全全部 `esl.*` 属性。

### 多个 FreeSWITCH（多实例）

`esl.inbounds.<名字>` / `esl.outbounds.<名字>` 按名字配置任意多个实例，每个 inbound 客户端连一台 FreeSWITCH。每个条目的可用属性与 `esl.inbound` / `esl.outbound` 相同，默认值也相同（不从 `esl.inbound` 继承），条目存在即启用（`enabled: false` 可关闭）。可以和单实例写法同时使用。

```yaml
esl:
  inbounds:
    fs1:
      addresses: [10.0.1.10:8021]
      events: [CHANNEL_ANSWER, CHANNEL_HANGUP_COMPLETE]
    fs2:
      addresses: [10.0.2.10:8021]
      password: secret
  outbounds:
    ivr:
      port: 8085
      handler: ivrHandler       # OutboundSessionHandler 的 bean 名；只有一个 handler 时可省略
    queue:
      port: 8086
      handler: queueHandler
```

```java
// 按名字注入；不带 @Qualifier 的 InboundClient 注入点只会拿到 esl.inbound 那个实例
CallService(@Qualifier("fs1") InboundClient fs1, EslClients clients) {
    clients.inbound("fs2").api("status");
    clients.inbounds().forEach((name, client) -> ...);
}

@EslEventListener(value = "CHANNEL_ANSWER", client = "fs1")   // 不写 client 则监听所有 inbound 客户端
void onAnswer(EslEvent event) { ... }
```

- 实例名默认是 map 的 key（可用 `name` 覆盖），用于日志、线程名、指标 tag、`@Qualifier` 和 `EslClients`；重名会让启动失败。
- 配了 `esl.outbounds` 时，`esl.outbound` 默认服务端只在设置了 `esl.outbound.handler` 时启动，避免多个 handler 时误启动。
- 自定义需要区分实例时，实现 customizer 的 `customize(String name, Builder builder)`。
- 健康检查：具名实例在 `eslInbounds` / `eslOutbounds` 下按名字展开（如 `/actuator/health/eslInbounds/fs1`）。
- 所有实例共享同一组 I/O 和工作线程（`esl.threads`）。

## 构建与发布

```bash
mvn clean verify
```

发布到 Maven Central（Central Portal）：在 `~/.m2/settings.xml` 中配置 id 为 `central` 的 server（Central Portal 的 user token），GPG 私钥放在本机 gpg 中，然后执行：

```bash
mvn -Prelease clean deploy
```

`autoPublish` 为 `false`，上传后需要在 central.sonatype.com 上确认发布。凭据不要写进项目文件。

## License

[MIT License](LICENSE) © 2026 Xiaodong Xu
