package com.acme.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单实体。
 *
 * @param orderNo    订单号
 * @param userId     用户 ID
 * @param amount     订单金额
 * @param status     订单状态
 * @param requestId  幂等键
 * @param createdAt  创建时间
 */
public record Order(
        String orderNo,
        Long userId,
        BigDecimal amount,
        OrderStatus status,
        String requestId,
        Instant createdAt
) {

    /** 订单状态。 */
    public enum OrderStatus {

        /** 已创建，等待支付。 */
        CREATED,

        /** 已支付。 */
        PAID,

        /** 已取消。 */
        CANCELLED
    }
}
