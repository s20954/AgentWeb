package com.minsu.tool.executor;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * 订单存储（内存，含幂等）。支撑 create_order / get_order / cancel_order。
 *
 * <p>幂等：用 idempotencyKey 记录已处理的下单请求，重复下单同 key 直接返回原订单，
 * 避免网络重试导致下两单（对齐《框架设计蓝图》第 6.5 节）。
 */
@Component
public class OrderStore {

    public enum Status { PENDING_PAYMENT, CONFIRMED, CANCELLED }

    public record Order(String orderId, String userId, String roomId,
                        LocalDate checkIn, LocalDate checkOut, int guests,
                        int totalAmount, Status status) {
    }

    private final ConcurrentMap<String, Order> ordersById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idempotencyKeyToOrderId = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);

    /**
     * 创建订单（幂等，原子）。用 {@code compute} 把「查-建-写」合成一个原子操作，
     * 并发下同 idempotencyKey 只会产生一个订单，返回同一订单。
     *
     * <p>安全修复（2026-11-17）：幂等键按 userId 命名空间化（{@code userId:idempotencyKey}）。
     * idempotencyKey 是模型/调用方可填参数，若只按 key 做幂等，用户 B 复用用户 A 的 key
     * 会直接拿到 A 的订单对象（订单信息泄露 + B 自己的下单被静默吞掉）。加 userId 前缀后
     * 不同用户的幂等空间天然隔离，同一用户重试仍命中同一订单。
     */
    public Order create(String userId, String roomId, LocalDate checkIn, LocalDate checkOut,
                        int guests, int totalAmount, String idempotencyKey) {
        String namespacedKey = (userId == null ? "" : userId) + ":" + idempotencyKey;
        String orderId = idempotencyKeyToOrderId.compute(namespacedKey, (k, existing) -> {
            if (existing != null) {
                return existing;
            }
            String newId = "MS" + System.currentTimeMillis() + "-" + seq.getAndIncrement();
            ordersById.put(newId, new Order(newId, userId, roomId, checkIn, checkOut, guests, totalAmount, Status.PENDING_PAYMENT));
            return newId;
        });
        // 二次防御：即便命中也校验归属，绝不把他人订单返回给调用方
        Order order = ordersById.get(orderId);
        if (order != null && userId != null && !userId.equals(order.userId())) {
            throw new IllegalStateException("幂等键命中了其他用户的订单，拒绝返回（userId=" + userId + "）");
        }
        return order;
    }

    public Order get(String orderId) {
        return ordersById.get(orderId);
    }

    /** 取消订单（原子）：仅在当前状态非 CANCELLED 时生效，避免并发取消的 TOCTOU。 */
    public Order cancel(String orderId) {
        return ordersById.computeIfPresent(orderId, (id, o) -> {
            if (o.status() == Status.CANCELLED) {
                return o; // 已取消，幂等返回
            }
            return new Order(o.orderId(), o.userId(), o.roomId(), o.checkIn(), o.checkOut(),
                    o.guests(), o.totalAmount(), Status.CANCELLED);
        });
    }

    /**
     * 支付订单（原子）：仅 PENDING_PAYMENT → CONFIRMED；已确认幂等返回，已取消不可支付。
     * 返回 null 表示订单不存在（由调用方按「订单不存在」处理）。
     */
    public Order pay(String orderId) {
        return ordersById.computeIfPresent(orderId, (id, o) -> {
            if (o.status() == Status.CONFIRMED) {
                return o; // 已确认，幂等返回
            }
            if (o.status() == Status.CANCELLED) {
                return o; // 已取消不可支付，原样返回（由调用方判断状态并报错）
            }
            return new Order(o.orderId(), o.userId(), o.roomId(), o.checkIn(), o.checkOut(),
                    o.guests(), o.totalAmount(), Status.CONFIRMED);
        });
    }
}
