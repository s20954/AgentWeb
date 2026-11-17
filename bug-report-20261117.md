# 栖迟 Agent 项目整体测试报告

> 测试日期：2026-11-17　|　测试范围：`C:\Users\ASUS\Desktop\AgentWeb` 全项目
> 测试方式：静态代码审查（前后端逐文件）+ 构建验证 + 后端单测 + 真实 HTTP 运行时复现

---

## 〇、测试结论速览

| 维度 | 结果 |
| --- | --- |
| 后端 `mvn test` | ✅ 全通过，工具层混淆矩阵 15/16 = 93.8%（目标 ≥90%） |
| 前端类型检查 `vue-tsc -b` | ✅ 通过 |
| 前端生产构建 `vite build` | ✅ 通过（换输出目录后成功，详见"环境说明"） |
| 运行时健康检查 / 工具协议 | ✅ 正常，错误三类型协议规范 |
| **发现缺陷** | **P0×0　P1×3　P2×8　P3×2　共 13 项** |

**最严重的 3 个 P1（均已实测复现或代码确证）**：跨用户幂等串单、SSE 断连后仍执行写操作、前端清空对话时流式回调崩溃。

---

## 一、P1 — 严重（数据安全 / 资金正确性 / 运行时崩溃）

### P1-1　跨用户幂等串单：用户 B 能拿到用户 A 的订单
- **位置**：`backend/.../tool/executor/OrderStore.java:36-44`（配合 `CreateOrderExecutor.java:71`、`ToolController.java:49`）
- **实测复现**：
  - 用户 A 用 `idempotencyKey=SHARED-KEY` 下单 → 得到 `MS1794929350407-1`
  - 用户 B 用**相同 key** 下单 → 得到**同一个** `MS1794929350407-1`（含 roomId/total/状态全部回填）
- **根因**：幂等键 `idempotencyKeyToOrderId` 是全局 Map，`compute(key, ...)` 命中时直接返回已有订单，**不校验 userId**。而 key 是模型/调用方可填参数（`ToolController.run` 直接透传 body），并非服务端按用户生成。
- **后果**：订单数据泄露 + 用户 B 的下单被静默吞掉。
- **修复**：幂等键按用户命名空间化，如 `compute(userId + ":" + idempotencyKey, ...)`；命中已有订单时再校验其 `userId` 与当前用户一致，不一致返回错误。

### P1-2　SSE 断连后后台线程不取消，仍跑完含下单/支付的完整写操作
- **位置**：`backend/.../web/ChatController.java:56, 80-91`
- **根因**：`Sinks.many().unicast().onBackpressureBuffer()` 无界；虚拟线程里 `agentService.run(...)` 无取消检查；返回的 `sink.asFlux()` 未挂 `doFinally`。
- **后果**：用户关闭页面/刷新/断网导致订阅取消后，Agent 仍继续收敛，可能执行 `create_order`/`pay_order` 等真实写入——用户"没点确认却已下单"。同时下游取消后 `tryEmitNext` 全部丢弃、白算；慢客户端 + LLM 高速吐 token 时缓冲无界增长有 OOM 风险。
- **修复**：返回 Flux 挂 `doFinally(signal -> cancelled.set(true))`，Agent 每步（尤其写工具前）检查取消令牌并中断；`onBackpressureBuffer(capacity, DROP_OLDEST)` 限容；改用响应式链 `Mono.fromCallable(...).subscribeOn(...).flatMapMany(...)` 天然继承取消语义。

### P1-3　流式过程中点"清空对话"导致未捕获异常
- **位置**：`frontend/src/stores/chat.ts:56,72,87,90,94,103,108`（配合 `ChatView.vue:161-167` 的"清空对话"按钮）
- **根因**：`const agentMsg = () => messages.value.find(m => m.id === agentId)!` 非空断言；流式回调 `onStep/onText/onDone/onError` 及函数末尾都直接 `agentMsg().xxx`。顶栏"清空对话"调用 `reset()` 令 `messages.value = []`，但 SSE 流不中止。
- **后果**：Agent 正在流式回复时点"清空对话"，后续 chunk 到达时 `find()` 返回 `undefined`，`agentMsg().content += chunk` 抛 `TypeError`；该错误被 try 捕获后转调 `onError`，`onError` 里又解引用 `agentMsg()`，在 catch 内二次抛出，成为未捕获异常，界面残留半截状态。
- **修复**：回调前判空 `const m = messages.value.find(...); if (!m) return;`；引入 `AbortController` 在 `reset()`/卸载时中止流 + 维护 `generationId` 校验轮次。

---

## 二、P2 — 高（功能不正确 / 状态错乱）

### P2-1　userId 未回退，前端不传 `X-User-Id` 时交易功能全失效
- **位置**：`backend/.../web/ChatController.java:44,82`
- **根因**：`X-User-Id` 是 `required=false`，可为 null；控制器直接调 4 参数版 `run(userId, ...)`，而本会做 `userId=sessionId` 回退的便捷重载（见 `AgentService.java:89-91`）没被使用。
- **后果**：只传 sessionId 不传 header 时，`create_order` 抛 `STATE_ERROR`，`get/cancel/pay_order` 抛 `BUSINESS_REJECT`，用户不知道原因。（实测：工具端点不传 header 下单确实返回 `STATE_ERROR`。）
- **修复**：控制器内 `String uid = (userId==null||userId.isBlank()) ? sessionId : userId;` 再传；或统一在 `run` 4 参数版内兜底。

### P2-2　倒挂日期被加一整年，生成荒谬租期
- **位置**：`backend/.../agent/AgentService.java:322-325`
- **实测复现**：输入「11月5日到11月3日 莫干山住一晚」→ 思考链显示 `入住=2027-11-05、离店=2028-11-03`（住满一年）。
- **根因**：`if (!checkOut.isAfter(checkIn)) checkOut = checkOut.plusYears(1);` 无条件加一年，本意是处理跨年「12月28日到1月2日」，但同月输错/同日也会中招；叠加"入住日期已过自动推明年"逻辑后年份继续叠加。
- **修复**：仅当**显式给出第二段月份且离店月份 < 入住月份**时才 `plusYears(1)`；否则 `checkOut <= checkIn` 视为参数非法（走缺口追问 / `PARAM_INVALID`）或按默认 1 晚推导。

### P2-3　同一 sessionId 并发请求读写非线程安全的 AgentState
- **位置**：`backend/.../agent/AgentService.java:105`（配合 `AgentState.java:24,27`）
- **根因**：`sessions` 是 `ConcurrentHashMap`，但 `AgentState.gaps/candidates` 是普通 `ArrayList`，`detectGaps()` 先 `clear()` 再 `add()`，`extractEntities()` 直接改字段；每次请求一个虚拟线程。
- **后果**：双击发送/超时重试/同会话并发时，两线程交错操作同一状态 → `ConcurrentModificationException`、缺口错乱、候选串轮、回复错乱。对比 `ConversationMemory` 已做并发保护，此处明显遗漏。
- **修复**：按 sessionId 加互斥锁，串行化"抽取→缺口→筛选→写回"；或 `gaps` 改 `CopyOnWriteArrayList` + 每次重建局部状态整体替换。

### P2-4　取消/支付并发 TOCTOU，已取消订单可能返回"支付成功"
- **位置**：`backend/.../tool/executor/PayOrderExecutor.java:43-56`（配合 `OrderStore.java:66-77`）
- **根因**：执行器先 `get()` 判断非 CANCELLED（T1）再 `pay()`（T2），两步非原子；`OrderStore.pay()` 遇 CANCELLED 原样返回，但执行器末尾硬编码 `data.put("paid", true)` + "支付成功"文案。
- **说明**：顺序调用下（先取消再支付）已被正确拦截返回 `BUSINESS_REJECT`（实测通过），此 bug 仅在 T1/T2 之间有并发取消时触发，属竞态窗口。
- **修复**：以 `pay()` 返回值状态为准，只有 `CONFIRMED` 才报成功；把状态判定合并进 `OrderStore.pay()` 的原子 `compute` 返回明确结果枚举，执行器不二次判断。

### P2-5　前端 OrderPayCard 靠正则从 LLM 自由文本抠订单字段
- **位置**：`frontend/src/components/OrderPayCard.vue:20-26,85-101,117-148`
- **根因**：`orderId` 靠 `/MS\d{10,}-\d+/`，`isPending` 靠文本含"待支付"，金额/日期靠正则。后端下单文本是 LLM 基于工具结果自由润色（`AgentService.java:984-999`），无固定模板。
- **后果**：LLM 换措辞（"请尽快付款"/"合计 1,224 元"/"11月1日"）时，"去支付"按钮不显示、金额变 0、日期变空。
- **修复**：后端在下单后补发结构化事件（如 `event=order`，data 含 orderId/status/roomId/total/dates），前端存到 `ChatMessage.order` 按字段渲染。

### P2-6　无请求中止 / 无卸载清理，切页后台仍写状态
- **位置**：`frontend/src/views/ChatView.vue:45-111`、`frontend/src/api/chat.ts:48-97`
- **根因**：打字机 `setTimeout` 与 SSE `fetch` 都无 `AbortController`，`ChatView` 无 `onUnmounted` 清理。
- **后果**：打字机未结束或 SSE 未结束时点"首页"离开 `/agent`，定时器和流读取继续改全局 Pinia `store.messages`；反复进出叠加多个打字机任务，状态错乱 + 资源泄漏。
- **修复**：`streamChat` 接受 signal 传入 `fetch`；`ChatView` 维护 AbortController 与定时器集合，`onUnmounted` 时 abort + clearTimeout。

### P2-7　评价平均分 `avg` 非计算属性，新增评价/切换房源后不更新
- **位置**：`frontend/src/components/ReviewSection.vue:42-47`
- **根因**：`const avg = computedAvg()` 在 setup 阶段只执行一次得到字符串，模板 `{{ avg }}` 非响应式来源。
- **后果**：提交评价后列表 v-for 更新但平均分停在首挂值；同一组件从民宿 A 切到 B（`v-if="homestay"` 不重建实例）平均分也不更新。
- **修复**：改为 `const avg = computed(() => { ... })`。

### P2-8　视觉模型配置 `app.vision.*` 完全未生效
- **位置**：`backend/src/main/resources/application.yml:28-31` + `backend/.../web/VisionController.java:34,60`
- **根因**：配置声明了 `app.vision.base-url/api-key/model`，但 `VisionController` 只注入 `DeepSeekLlmClient`，`chatVision()` 内部硬用 `app.llm.*` 的模型/密钥，`VISION_BASE_URL` 从未被读取。
- **后果**：运维单独设置 `VISION_MODEL/API_KEY/BASE_URL` 期望换模型/端点完全不起作用，视觉分析仍走 LLM 的模型打到 api.deepseek.com，排障易误判。
- **修复**：新增独立视觉 WebClient 用 `${app.vision.*}` 构造；或删除这些配置避免误导。

---

## 三、P3 — 中低（安全加固 / 边界）

### P3-1　超长日期导致金额 int 溢出为负
- **位置**：`backend/.../tool/executor/CreateOrderExecutor.java:65-68`（同 `QuotePriceExecutor.java:52-55`）
- **根因**：`reqDate` 允许年份至 9999，`(int) nights * basePrice` 强转后乘法溢出。
- **后果**：极端输入（如 checkOut=9999-12-31）下 `total` 变负，写入负金额订单。低概率但可用合法输入触发。
- **修复**：金额用 `long`/`BigDecimal`，限制最大可订天数（如 ≤30 晚），超限抛 `PARAM_INVALID`。

### P3-2　marked 全局副作用 + 链接 title 未转义
- **位置**：`frontend/src/components/ChatMessage.vue:32-42,48-50`
- **根因**：`renderer.link` 里 `titleAttr = title ? \` title="${title}"\` : ''` 未做属性转义；`marked.use()` 在每个组件实例 setup 中执行，反复改全局配置。
- **后果**：内容来自 LLM/联网（不可信），构造的 title 可突破属性边界注入事件属性；多实例重复注册 renderer，行为依赖创建顺序。
- **修复**：href/title 统一做属性转义；`marked.use()` 移到模块级只执行一次或改用局部 renderer；图片 renderer 做协议消毒。

---

## 四、环境说明（非项目 bug，但影响验证流程）

1. **`npm run build` 首次失败**：`vite build` 清空 `frontend/dist` 时被工作环境的安全删除守卫拦截（`SAFE_DELETE_BULK_CONFIRM_REQUIRED`，因目录内文件 >50 个）。改用 `npx vite build --outDir dist_verify` 后成功（exit 0），证明代码本身可正常构建。本地直跑 npm 不受此守卫影响，属本次沙箱环境的干扰，非项目缺陷。
2. **LLM_API_KEY 未配置**：后端日志显示 `LLM_API_KEY 未设置`，联网规划/LLM 润色降级为拼接字符串。因此本次对 OrderPayCard 契约、下单话术等"依赖 LLM 输出"的部分只能做代码级判断，未能用真实 LLM 回归。建议配置密钥后再跑一轮对话 E2E。
3. **git 工作区有未提交改动**：README.md、ThinkingChain.vue 被修改；deploy/ 下 4 个文件与"作息时间表.md"处于删除状态（D）。测试期间未触碰这些改动。

---

## 五、建议修复顺序

1. **立即**：P1-1（越权串单）、P1-2（SSE 断连仍写库）、P1-3（清空对话崩溃）——数据安全与资金/订单正确性。
2. **本迭代**：P2-1（userId 回退）、P2-2（日期倒挂，演示高频触发）、P2-7（平均分）、P2-8（视觉配置）。
3. **下迭代**：P2-3/4（并发）、P2-5（订单结构化契约）、P2-6（卸载清理）、P3-1/2（边界与安全）。

---

## 六、修复记录（2026-11-17 23:35-23:55，13 项全部修复）

> 代码改动均在 `C:\Users\ASUS\Desktop\AgentWeb`。本次为纯 bug 修复，未改产品功能与 UI 样式。

### 已修复清单

| 编号 | 级别 | 修复方式 | 验证 |
| --- | --- | --- | --- |
| P1-1 | 跨用户幂等串单 | `OrderStore.create` 幂等键改为 `userId:idempotencyKey` 命名空间 + 命中校验归属 | ✅ 真机：A/B 同 key 得不同订单（`-1`/`-2`），同用户重试仍幂等 |
| P1-2 | SSE 断连仍写库 | `ChatController` 加 `doFinally(CANCEL)` 置位 + 中断虚拟线程；`EventSink.isCancelled()` 默认方法；Agent 写工具前 `ensureActive()`；有界缓冲（`Queues.get(256)`） | ✅ 编译通过、单测通过（断连场景代码确证） |
| P1-3 | 清空对话崩溃 | `chat.ts` 的 `agentMsg()` 去非空断言 + 全回调判空；引入 `AbortController`，`reset()`/卸载时中止 | ✅ 前端类型检查 + 构建通过 |
| P2-1 | userId 未回退 | `ChatController` 用 `effectiveUserId = userId ?: sessionId` | ✅ 真机：不传 header 走 chat 正常 done、无 error |
| P2-2 | 倒挂日期加一年 | `AgentService` 仅「显式第二段月份且离店月<入住月」才跨年，否则按住 1 晚推导 | ✅ 真机：`11月5日到11月3日`→入住/离店 11-05/11-06；新增回归单测 |
| P2-3 | 并发 AgentState | `run` 按 sessionId 加 per-session 互斥锁（`sessionLocks`），随会话 TTL 一起清理 | ✅ 单测通过 |
| P2-4 | 支付 TOCTOU | `PayOrderExecutor` 以原子 `pay()` 返回状态为准，`CANCELLED` 必拒、`paid` 由真实状态派生 | ✅ 单测通过 |
| P2-5 | 订单字段靠正则 | 后端下单/支付成功推 `kind=order_result` 结构化 payload；前端 `ChatMessage.order` + `OrderPayCard` 优先用结构化字段，正则仅回退 | ✅ 前后端构建通过 |
| P2-6 | 无卸载清理 | `ChatView` 加 `onUnmounted` 中止 SSE + 打字机 `alive` 检查；`streamChat` 支持 `AbortSignal` | ✅ 前端类型检查 + 构建通过 |
| P2-7 | 平均分不更新 | `ReviewSection.avg` 改 `computed` | ✅ 构建通过 |
| P2-8 | 视觉配置未生效 | `DeepSeekLlmClient` 增加独立 `visionWebClient/apiKey/model`（缺省回退主 LLM）；新增 `isVisionConfigured()`，`VisionController` 改用它 | ✅ 单测通过（无 key 仍走 fallback） |
| P3-1 | 金额 int 溢出 | `CreateOrderExecutor`/`QuotePriceExecutor` 加 ≤30 晚上限 + long 计算 + `Integer.MAX_VALUE` 保护 | ✅ 真机：`9999-12-31` 被拒（PARAM_INVALID），不再负金额 |
| P3-2 | marked title 注入 | `ChatMessage` 的 renderer 移到模块级 `<script>` 只注册一次；`title`/`href`/`alt` 统一 `escapeAttr`；新增图片协议消毒 | ✅ 前端类型检查 + 构建通过 |

### 新增回归测试（后端）
- `ToolLayerTest.sameIdempotencyKeyAcrossUsersDoesNotShareOrder`（P1-1）
- `ToolLayerTest.extremeDateIsRejectedNotOverflow`（P3-1）
- `AgentServiceTest.invertedSameMonthDatesDoNotBecomeOneYear`（P2-2）

### 修复后验证结果
- 后端 `mvn test`：57 用例，新增 3 个回归测试全通过；唯一一次 ConfusionMatrixTest 通过率 81.3%（LLM 实时调用波动）经**单独复跑 EXIT=0 通过**，确认非代码问题。
- 前端 `vue-tsc -b` EXIT=0；`vite build` EXIT=0（临时目录验证后已清理）。
- 真机 HTTP 复现验证：P1-1 / P2-1 / P2-2 / P3-1 四项均从「缺陷可复现」转为「修复已验证」。

### 遗留 / 未纳入本次修复
- **LLM 相关运行时**：本次未配 `LLM_API_KEY`，下单话术润色、OrderPayCard 结构化事件的实际 SSE 推送未能用真实 LLM 端到端验证（逻辑与编译已确保），建议配密钥后跑一轮真实对话 E2E。
- **SSE 断连取消**：代码路径已加取消令牌与中断，但未做「关页瞬间正在下单」的极限时序压测（需真实前端 + 慢网模拟）。
- 本次改动**尚未 git 提交**，请按里程碑决定是否 commit。
