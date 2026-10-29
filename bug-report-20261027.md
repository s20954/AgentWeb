# 民宿预约 Agent — Bug 测试报告

- **日期**：2026-10-27
- **范围**：backend（Spring Boot 3 + WebFlux 工具层）、frontend（Vue3 + TS + Pinia）
- **方法**：`mvn test` 全量单测 + `vue-tsc && vite build` 类型检查/构建 + 启动后端对 `/api/tools/**` 做实打实 HTTP 验证（含并发）

## 一、总体结论

| 项目 | 结果 |
| --- | --- |
| 后端单测（HealthControllerTest + ToolLayerTest，12 用例） | ✅ 全部通过 |
| 前端类型检查 + 生产构建 | ✅ 通过 |
| 实网 HTTP 验证 | ❌ 发现 2 个高危 bug（越权、幂等并发竞态）+ 2 中 + 2 低 |

> 单测全绿但实网仍中招的原因：现有测试全部单线程、且未覆盖「跨用户访问订单」场景。

---

## 二、高危 Bug（均已实测复现）

### BUG-1 订单越权：任意用户可查/取消他人订单

- **严重度**：🔴 高（安全/数据隔离问题）
- **位置**：
  - `backend/src/main/java/com/minsu/tool/executor/GetOrderExecutor.java`（execute 方法，L27-46）
  - `backend/src/main/java/com/minsu/tool/executor/CancelOrderExecutor.java`（execute 方法，L27-48）
- **现象**：`userId` 已从请求头 `X-User-Id` 注入 `ToolContext`（模型不可填，设计正确），但两个执行器都**从未比对** `order.userId()` 与 `ctx.userId()`。
- **实测证据**：
  1. `user-A` 调 `create_order` 下单成功 → `orderId=MS17930881857981`
  2. `user-B` 调 `get_order` 查该单 → `success=true`，返回完整订单详情（含金额 1004）
  3. `user-B` 调 `cancel_order` 取消该单 → `success=true`，状态变 `CANCELLED`
- **修复建议**：在 `get_order` / `cancel_order` 执行器中加入归属校验：

```java
if (ctx.userId() == null || !ctx.userId().equals(order.userId())) {
    throw new ToolException(ToolErrorType.BUSINESS_REJECT,
            "无权操作订单 " + orderId + "，请确认是否为本人订单（不重试）。");
}
```

### BUG-2 幂等键并发竞态：同一幂等键产生重复订单

- **严重度**：🔴 高（幂等机制在并发下失效，恰是它要防的场景）
- **位置**：`backend/src/main/java/com/minsu/tool/executor/OrderStore.java`（create 方法，L28-39）
- **现象**：`create` 是「先 `get` 判断、再 `put` 写入」的 check-then-act，**非原子**。并发下同 key 请求会同时通过判空检查，各自创建订单。
- **实测证据**：同一 `idempotencyKey=race-test-2` 并发 8 个 `create_order` 请求 → 返回 **2 个不同 orderId**（`MS17930882314112`、`MS17930882314113`）。
- **修复建议**：用 `ConcurrentHashMap.compute` 把「查-建-写」合成一个原子操作：

```java
public Order create(String userId, String roomId, LocalDate checkIn, LocalDate checkOut,
                    int guests, int totalAmount, String idempotencyKey) {
    String orderId = idempotencyKeyToOrderId.compute(idempotencyKey, (k, existing) -> {
        if (existing != null) return existing;
        String newId = "MS" + System.currentTimeMillis() + "-" + seq.getAndIncrement();
        ordersById.put(newId, new Order(newId, userId, roomId, checkIn, checkOut, guests, totalAmount, Status.PENDING_PAYMENT));
        return newId;
    });
    return ordersById.get(orderId);
}
```

---

## 三、中危问题

### BUG-3 `search_rooms` 缺日期顺序校验，三处口径不一致

- **位置**：`backend/src/main/java/com/minsu/tool/executor/SearchRoomsExecutor.java`（execute，L27-38）
- **实测**：`checkIn=2026-11-05, checkOut=2026-11-01`（倒挂）→ `success=true` 且返回 7 条房源；而 `quote_price` / `create_order` 对同样日期会抛 `PARAM_INVALID`。
- **影响**：Agent 在「筛」阶段拿到的结果基于非法日期，后续报价/下单才报错，浪费一轮对话。
- **修复建议**：在 `SearchRoomsExecutor` 解析日期后补 `checkOut.isAfter(checkIn)` 校验，与 `QuotePriceExecutor` 逻辑对齐。

### BUG-4 `@EnableWebFlux` 误用，关闭 Spring Boot 自动配置

- **位置**：`backend/src/main/java/com/minsu/config/CorsConfig.java`（L16）
- **现象**：Spring Boot 项目中加 `@EnableWebFlux` 会**完全接管 WebFlux 配置**，Boot 的自动装配（统一错误处理、codec、`application.yml` 的 `spring.webflux.*` 等）全部失效。当前功能碰巧未受影响，但后续接 SSE 流式代理、全局异常处理器时会踩坑。
- **修复建议**：直接删除 `@EnableWebFlux` 注解。同时该类自身 `implements WebFluxConfigurer` 是死代码（未重写任何方法），可一并去掉，只留 `corsConfigurer` Bean。

---

## 四、低危问题

### BUG-5 空 body 调 `/run` 返回裸 400，破坏工具错误协议

- **位置**：`backend/src/main/java/com/minsu/web/ToolController.java`（run，L46-52）
- **实测**：POST `/api/tools/get_order/run` 不带 body → Spring 默认 400 错误页（`{"status":400,...}`），而非工具标准错误结构 `{errorType, retryable, message}`。Agent 层拿到 400 无法按「错误三类型」决策。
- **修复建议**：`@RequestBody(required = false)`，方法体内对 `args == null` 走 `PARAM_INVALID` 返回。

### BUG-6 `quote_price` 不校验 `guests > 0`

- **位置**：`backend/src/main/java/com/minsu/tool/executor/QuotePriceExecutor.java`（L33）
- **实测**：`guests=0` → `success=true`；而 `create_order` 会拒绝非正整数。口径不一致。
- **附带问题**：同一行 `optInt(args, "guests")` 被调用了两次（三元表达式里），无效重复解析。
- **修复建议**：先取一次 `Integer guestsVal = optInt(...)`，再判空给默认值，并补 `guests <= 0` 校验。

---

## 五、建议项（非 bug，可不改）

| # | 位置 | 说明 |
| --- | --- | --- |
| S-1 | `ToolDispatcher.java` L30-42 | 手动 `new ToolRegistry()` + new 全部执行器，而 `ToolRegistry` 本身是 `@Component`，容器中存在两个实例。后续执行器需注入 LLM client / DB 时会整体返工，建议改构造器注入。 |
| S-2 | `OrderStore.java` L34 | 订单号 `"MS" + timestamp + seq` 无分隔符（如 `MS17930882314112`），排障时可读性差，建议 `"MS" + timestamp + "-" + seq`。 |
| S-3 | `CancelOrderExecutor.java` L30-40 | 「已取消则拒绝」的检查与 `cancel` 之间是 TOCTOU，并发取消时两个请求都会成功。影响小于 BUG-2，可随 BUG-2 一起用原子操作解决。 |
| S-4 | `SearchRoomsExecutor.java` L29-30 | `checkIn` / `checkOut` 局部变量是死代码（实际用 `reqDate` 内联校验），可删。 |
| S-5 | 前端 `stores/app.ts` | 逻辑简单（仅健康检查冒烟），本轮未发现 bug。 |

---

## 六、修复优先级建议

1. **BUG-1（越权）+ BUG-2（幂等竞态）**：下一轮必须修，直接影响「AI 可控」评分点与数据安全。
2. **BUG-3 + BUG-6（校验口径）**：一起修，保持 6 工具的参数校验一致性。
3. **BUG-4（@EnableWebFlux）**：接 LLM 流式代理之前必须删掉。
4. BUG-5 与建议项可随 Agent 层开发顺带处理。
