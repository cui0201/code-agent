package com.acme.order;

import java.util.Optional;

/**
 * 订单仓储。
 *
 * <p>样例中只给出接口，真实项目中通常由 JPA 或 MyBatis 提供实现。
 */
public interface OrderRepository {

    /** 按幂等键查询已存在的订单。 */
    Optional<Order> findByRequestId(String requestId);

    /** 保存订单。 */
    Order save(Order order);

    /** 按订单号查询。 */
    Optional<Order> findByOrderNo(String orderNo);
}
