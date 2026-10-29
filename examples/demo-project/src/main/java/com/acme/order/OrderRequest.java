package com.acme.order;

import java.math.BigDecimal;

/**
 * 下单请求参数。
 *
 * @param userId      下单用户 ID
 * @param skuId       商品 ID
 * @param amount      订单金额，单位元
 * @param requestId   客户端幂等键，同一用户重复提交时携带相同值
 * @param couponCode  优惠券码，可为空
 */
public record OrderRequest(
        Long userId,
        Long skuId,
        BigDecimal amount,
        String requestId,
        String couponCode
) {

    /** 是否使用了优惠券。 */
    public boolean hasCoupon() {
        return couponCode != null && !couponCode.isBlank();
    }
}
