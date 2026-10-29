package com.acme.order;

/** 风控服务客户端。 */
public interface RiskClient {

    /**
     * 判断用户是否允许下单。
     *
     * @param userId    用户 ID
     * @param dailyLimit 当日下单次数上限
     * @return true 表示允许下单
     */
    boolean allowOrder(Long userId, int dailyLimit);
}
