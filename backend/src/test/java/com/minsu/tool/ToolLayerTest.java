package com.minsu.tool;

import java.util.List;
import java.util.Map;

import com.minsu.rag.KbStore;
import com.minsu.rag.RagService;
import com.minsu.tool.executor.OrderStore;
import com.minsu.tool.executor.RoomStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具层测试 —— 对齐《框架设计蓝图》第 6 章验收标准。
 *
 * <p>覆盖：6 工具 Schema 齐全 / 六槽位 / 错误三类型 / 幂等 / 鉴权从上下文取 / 返回要瘦。
 */
class ToolLayerTest {

    private ToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ToolDispatcher(new ToolRegistry(), new RoomStore(), new OrderStore(),
                new RagService(new KbStore(new com.minsu.rag.KbRepository(
                        System.getProperty("java.io.tmpdir") + "/minsu-kb-test-" + System.nanoTime() + ".db"))));
    }

    // ---------------------------------------------------------------- Schema

    @Test
    void registryHasAllSixTools() {
        List<String> names = dispatcher.registry().all().stream().map(t -> t.name()).toList();
        assertEquals(6, names.size(), "应恰好 6 个工具");
        assertTrue(names.containsAll(List.of(
                "search_rooms", "quote_price", "create_order", "get_order", "cancel_order", "ask_house_info")));
    }

    @Test
    void eachToolHasSixSlots() {
        for (var t : dispatcher.registry().all()) {
            assertNotNull(t.name());
            assertNotNull(t.description(), t.name() + " 缺「做什么/何时调」");
            assertNotNull(t.boundary(), t.name() + " 缺「边界句」");
            assertNotNull(t.precondition(), t.name() + " 缺「前置条件」");
            assertNotNull(t.parameters(), t.name() + " 缺「参数格式」");
            assertNotNull(t.returns(), t.name() + " 缺「返回什么」");
        }
    }

    @Test
    void writeToolsRequireConfirm() {
        assertTrue(dispatcher.registry().get("create_order").requiresConfirm());
        assertTrue(dispatcher.registry().get("cancel_order").requiresConfirm());
        assertFalse(dispatcher.registry().get("search_rooms").requiresConfirm());
        assertFalse(dispatcher.registry().get("quote_price").requiresConfirm());
    }

    // ---------------------------------------------------------------- 正常链路

    @Test
    void searchRoomsReturnsCandidates() {
        ToolResult r = dispatcher.dispatch("u1", "search_rooms", Map.of(
                "destination", "莫干山", "checkIn", "2026-11-01", "checkOut", "2026-11-03"));
        assertTrue(r.success());
        assertEquals("search_rooms", r.toolName());
        assertEquals(7, r.data().get("count"));
    }

    @Test
    void quotePriceReturnsTotal() {
        ToolResult r = dispatcher.dispatch("u1", "quote_price", Map.of(
                "roomId", "yg-001", "checkIn", "2026-11-01", "checkOut", "2026-11-03"));
        assertTrue(r.success());
        @SuppressWarnings("unchecked")
        Map<String, Integer> fee = (Map<String, Integer>) r.data().get("fee");
        int total = (int) r.data().get("total");
        // 2 晚 * 420 = 840 房费 + 80 清洁 + 84 服务 = 1004
        assertEquals(1004, total);
        assertEquals(840, fee.get("roomFee"));
        assertEquals(80, fee.get("cleaning"));
        assertEquals(84, fee.get("service"));
    }

    @Test
    void fullOrderFlow() {
        // 下单
        ToolResult create = dispatcher.dispatch("u1", "create_order", Map.of(
                "roomId", "yg-001", "checkIn", "2026-11-01", "checkOut", "2026-11-03",
                "guests", 2, "idempotencyKey", "idem-1"));
        assertTrue(create.success());
        String orderId = (String) create.data().get("orderId");

        // 查单
        ToolResult get = dispatcher.dispatch("u1", "get_order", Map.of("orderId", orderId));
        assertTrue(get.success());
        assertEquals("PENDING_PAYMENT", get.data().get("status"));

        // 取消
        ToolResult cancel = dispatcher.dispatch("u1", "cancel_order", Map.of("orderId", orderId));
        assertTrue(cancel.success());
        assertEquals("CANCELLED", cancel.data().get("status"));
    }

    // ---------------------------------------------------------------- 幂等

    @Test
    void createOrderIsIdempotent() {
        Map<String, Object> args = Map.of(
                "roomId", "yg-001", "checkIn", "2026-11-01", "checkOut", "2026-11-03",
                "guests", 2, "idempotencyKey", "idem-same");

        ToolResult r1 = dispatcher.dispatch("u1", "create_order", args);
        ToolResult r2 = dispatcher.dispatch("u1", "create_order", args);

        assertEquals(r1.data().get("orderId"), r2.data().get("orderId"),
                "同 idempotencyKey 应返回同一订单（幂等）");
    }

    // ---------------------------------------------------------------- 错误三类型

    @Test
    void paramInvalidIsRetryable() {
        ToolResult r = dispatcher.dispatch("u1", "search_rooms", Map.of(
                "destination", "莫干山")); // 缺 checkIn/checkOut
        assertFalse(r.success());
        assertEquals("PARAM_INVALID", r.data().get("errorType"));
        assertEquals(true, r.data().get("retryable"));
    }

    @Test
    void businessRejectNotRetryable() {
        // 查不存在的订单 → 业务拒绝，不重试
        ToolResult r = dispatcher.dispatch("u1", "get_order", Map.of("orderId", "MS-NOT-EXIST"));
        assertFalse(r.success());
        assertEquals("BUSINESS_REJECT", r.data().get("errorType"));
        assertEquals(false, r.data().get("retryable"));
    }

    @Test
    void stateErrorWhenNoUser() {
        // 鉴权从上下文取：userId 为空 → 状态错误，不可重试
        ToolResult r = dispatcher.dispatch("", "create_order", Map.of(
                "roomId", "yg-001", "checkIn", "2026-11-01", "checkOut", "2026-11-03",
                "guests", 2, "idempotencyKey", "idem-2"));
        assertFalse(r.success());
        assertEquals("STATE_ERROR", r.data().get("errorType"));
        assertEquals(false, r.data().get("retryable"));
    }

    @Test
    void unknownToolReturnsParamInvalid() {
        ToolResult r = dispatcher.dispatch("u1", "no_such_tool", Map.of());
        assertFalse(r.success());
        assertEquals("PARAM_INVALID", r.data().get("errorType"));
    }

    // ---------------------------------------------------------------- 边界句（相邻工具混淆）

    @Test
    void searchRoomsBoundaryDistinguishesQuotePrice() {
        String b = dispatcher.registry().get("search_rooms").boundary();
        assertTrue(b.contains("quote_price"), "search_rooms 边界句应指向 quote_price");
        assertTrue(b.contains("roomId"), "边界句应含客观判据 roomId");
    }

    @Test
    void quotePriceBoundaryDistinguishesSearchRooms() {
        String b = dispatcher.registry().get("quote_price").boundary();
        assertTrue(b.contains("search_rooms"), "quote_price 边界句应指向 search_rooms");
    }

    // ---------------------------------------------------------------- Bug 修复回归

    @Test
    void bug1_cannotAccessOtherUsersOrder() {
        // user-A 下单
        ToolResult create = dispatcher.dispatch("user-A", "create_order", Map.of(
                "roomId", "yg-001", "checkIn", "2026-11-01", "checkOut", "2026-11-03",
                "guests", 2, "idempotencyKey", "idem-a"));
        assertTrue(create.success());
        String orderId = (String) create.data().get("orderId");

        // user-B 查他人订单 → 越权拒绝
        ToolResult get = dispatcher.dispatch("user-B", "get_order", Map.of("orderId", orderId));
        assertFalse(get.success());
        assertEquals("BUSINESS_REJECT", get.data().get("errorType"));

        // user-B 取消他人订单 → 越权拒绝
        ToolResult cancel = dispatcher.dispatch("user-B", "cancel_order", Map.of("orderId", orderId));
        assertFalse(cancel.success());
        assertEquals("BUSINESS_REJECT", cancel.data().get("errorType"));

        // 本人仍可查
        ToolResult self = dispatcher.dispatch("user-A", "get_order", Map.of("orderId", orderId));
        assertTrue(self.success());
    }

    @Test
    void bug2_idempotencyConcurrent() throws Exception {
        int n = 32;
        var latch = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new Thread(() -> {
                try {
                    latch.await();
                    ToolResult r = dispatcher.dispatch("u1", "create_order", Map.of(
                            "roomId", "yg-001", "checkIn", "2026-11-01", "checkOut", "2026-11-03",
                            "guests", 2, "idempotencyKey", "idem-race"));
                    results.put(Thread.currentThread().getName(), (String) r.data().get("orderId"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        latch.countDown();
        for (Thread t : threads) {
            t.join();
        }
        // 所有并发请求应返回同一 orderId
        assertEquals(1, results.values().stream().distinct().count(),
                "同 idempotencyKey 并发下应只产生一个订单");
    }

    @Test
    void bug3_searchRoomsRejectsInvertedDates() {
        ToolResult r = dispatcher.dispatch("u1", "search_rooms", Map.of(
                "destination", "莫干山", "checkIn", "2026-11-05", "checkOut", "2026-11-01"));
        assertFalse(r.success());
        assertEquals("PARAM_INVALID", r.data().get("errorType"));
    }

    @Test
    void bug6_quotePriceRejectsZeroGuests() {
        ToolResult r = dispatcher.dispatch("u1", "quote_price", Map.of(
                "roomId", "yg-001", "checkIn", "2026-11-01", "checkOut", "2026-11-03",
                "guests", 0));
        assertFalse(r.success());
        assertEquals("PARAM_INVALID", r.data().get("errorType"));
    }

    @Test
    void bug5_emptyArgsReturnsToolErrorNotBare400() {
        // 模拟空 body：args 为空 Map，也应返回工具标准错误（PARAM_INVALID）而非异常
        ToolResult r = dispatcher.dispatch("u1", "get_order", Map.of());
        assertFalse(r.success());
        assertEquals("PARAM_INVALID", r.data().get("errorType"));
    }
}
