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
     */
    public Order create(String userId, String roomId, LocalDate checkIn, LocalDate checkOut,
                        int guests, int totalAmount, String idempotencyKey) {
        String orderId = idempotencyKeyToOrderId.compute(idempotencyKey, (k, existing) -> {
            if (existing != null) {
                return existing;
            }
            String newId = "MS" + System.currentTimeMillis() + "-" + seq.getAndIncrement();
            ordersById.put(newId, new Order(newId, userId, roomId, checkIn, checkOut, guests, totalAmount, Status.PENDING_PAYMENT));
            return newId;
        });
        return ordersById.get(orderId);
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
}
