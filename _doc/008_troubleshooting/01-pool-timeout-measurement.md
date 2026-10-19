# 池空建连那一段的界是 maxWait，socketTimeout 管的是单次尝试（2026-10-04 实测 z-schedule）

## 结论

`ZSchedulePoolConnectTimeoutTest` 3 条失败的根因是**测试的立论前提错了**，不是生产代码有 bug。
生产代码（`applyDriverConnectTimeouts` 把 `connectTimeout`/`socketTimeout`
递进 `connectProperties`）**是对的**，实测能验证到它确实递进去了、也确实生效了。

两个旋钮与 `maxWait` 是**「总界 / 单次尝试」两层**，互不冲突：

| 旋钮 | 管哪一段 | 作用位置 |
|---|---|---|
| `maxWait` | 池空建连的**整段总界** | 调用线程 `getConnection` |
| `socketTimeout` | **每一次**物理连接尝试 | 后台 `CreateConnectionThread` |
| `connectTimeout` | 单次尝试里"三次握手都完不成"那一档 | 同上 |

## 源码依据（Druid 1.2.24，`com/alibaba/druid/pool/DruidDataSource.java`）

```
调用线程 getConnection(maxWait)
  └─ getConnectionInternal:  expiredTime = now + maxWait                      :1369-1370
       ├─ pollLast(startTime, expiredTime)   在池的 notEmpty 上等到 expiredTime :1475
       └─ 到点 holder 仍为 null ⇒ GetConnectionTimeoutException(msg, createError) :1513-1571
后台 CreateConnectionThread（被 empty.signal 唤醒，守护线程）                   :2533-2639
  └─ 循环 createPhysicalConnection()
       └─ connectTimeout / socketTimeout 作用在每一次尝试上
       └─ 失败记进池的 createError；失败后歇 timeBetweenConnectErrorMillis 再试
```

所以到期者永远是 Druid 自己（`GetConnectionTimeoutException`），
驱动旋钮失败则作为它的 `cause` 附在链上（`createError`）。

## 复现与实测读数

黑洞 = `new ServerSocket(0, 8, loopback)` 绑上但从不 `accept`
（内核完成三次握手，驱动的读永远等不到 MySQL 的第一个包）。
`initial-size=0` / `min-idle=0`，否则 init() 会连着建 5 条把计时摊成 5 倍。

| 场景 | maxWait | 递的旋钮 | 实测耗时 | 异常链 |
|---|---|---|---|---|
| 裸驱动 | — | `socketTimeout=1500` | **1599 ms** | `CommunicationsException` |
| Druid | 1000 | `socketTimeout=1500` | 1015 / 1017 ms | `GetConnectionTimeoutException`（**无 cause**） |
| Druid | 1000 | `socketTimeout=5000` | 1017 ms | `GetConnectionTimeoutException`（无 cause） |
| Druid | 20000 | `socketTimeout=1500` | 20018 ms | `GetConnectionTimeoutException` |
| Druid | 30000 | `socketTimeout=1500`+`connectTimeout=5000` | 30016 ms | `GetConnectionTimeoutException` |
| Druid | 30000 | 旋钮**内联进 URL** | 3016 ms | `GetConnectionTimeoutException` |
| Druid | 30000 | 只在 `connectProperties` | 30017 ms | `GetConnectionTimeoutException` |
| Druid | 30000 | `socketTimeout=20000` | 3006 ms | `GetConnectionTimeoutException`（无 cause） |
| **Druid** | **3000** | **`socketTimeout=200`** | **3006 ms** | `GetConnectionTimeoutException ← CommunicationsException ← CJCommunicationsException ← **SocketTimeoutException**` |

**每一次 Druid 的耗时都精确贴着 `maxWait`**，而同一组旋钮在裸驱动上是 1599 ms。

### 最后两行是关键：谁先到点

- `maxWait(1000) < socketTimeout(1500)` ⇒ 到点时那次尝试还在飞，`createError` 仍为 null，
  异常**没有 cause**。
- `socketTimeout(200) < maxWait(3000)` ⇒ 那次尝试早在 200 ms 就失败并记进 `createError`，
  驱动**照常重试**，调用线程仍等到 3000 ms 才收尾，**并把那次失败作为 cause 附上**。

⇒ `socketTimeout` 在建连路径上**不是没生效**，而是它的生效表现为
"cause 链上出现 `SocketTimeoutException`"；总界始终是 `maxWait`。

## 原来的立论（错的）

测试类注释写的是：

> 池空时 Druid 在调用线程里同步 connect，那一段**既不受 maxWait 约束**，
> 也没人给它设驱动超时。所以两支计时测试都把 max-wait 压到 1000 ms ——
> 如果 maxWait 真能定界，控制支会在 1 s 就抛；它偏不。

**"它偏不"是错的** —— 它就在 1.007 s 抛了。控制支
`不设读超时时哪怕maxWait一秒也会一直卡住` 失败于 1.007 s，
正是 `maxWait=1000` 到点。这就是"控制支反而证明了被否掉的解释"。

顺带订正：原文说「调用线程里同步 connect」。源码上，**同步建连只发生在
`createScheduler` 已配置且其队列非空时**（`:1455-1470`），默认没有 `createScheduler`
⇒ 走的是 `pollLast` 等到 `expiredTime`。后台创建线程负责尝试，调用线程只负责等。

## 真正有价值的另一个形状

`socketTimeout` 还管**「借到一条已经建立、但对端不再回包的老连接」**之后的查询读包：

    testOnBorrow=false（刻意的，依赖 testWhileIdle）⇒ 借出瞬间成功
    ⇒ 没有任何 maxWait 在前面（连接是借的，不是新建的）
    ⇒ 查询的第一个读包无界  ⇒  只有 socketTimeout 管得住

这正是 §23 线上量到的 `/actuator/health` 75 s 无应答那一档。
**要造出这个形状**：先让池建好一条真连接，再让服务端停止回包，
然后借出并查询。本机没有 MySQL 服务端，造不出来 —— 与原注释的结论一致。
`connectTimeout` 的「SYN 被丢弃」形状同理（要不可路由地址），也造不出来。

## 对生产配置的结论

`DEFAULT_SCHEDULE_SOCKET_TIMEOUT_MILLIS = 60000` **仍然是对的**，理由不变：
它管的是"借到的老连接上的读包"与"单次建连尝试"，不是整段总界（那是 `maxWait`，默认 60 s）。
`DEFAULT_SCHEDULE_CONNECT_TIMEOUT_MILLIS = 5000` 同理。
两个旋钮与 `maxWait` 各管一层，**不冲突、不冗余**，无需改动生产配置。

## 测例怎么钉住这个结论

- `建连那一段的界是maxWait而不是驱动超时`：不设 `socketTimeout`，
  **配对**断言 maxWait=1000→约 1 s、maxWait=3000→约 3 s（耗时必须跟着 maxWait 走，
  只钉单点会被巧合骗过）。
- `读超时只管单次尝试而整段的界仍是maxWait`：`maxWait=3000` + `socketTimeout=200`，
  断言耗时 ∈ [2000, 8000]（真读数 3006，两侧都留 1000 ms 余量，不贴边）、
  最外层是 `GetConnectionTimeoutException`、**cause 链里必须有 `SocketTimeoutException`**。
  最后这条同时钉住"旋钮确实递到了驱动"。
  反向可证：把 `applyDriverConnectTimeouts` 里的 `socketTimeout` 接线去掉，本支即报红。

## 复跑

    mvn -o -pl z-schedule-spring-boot-starter test
