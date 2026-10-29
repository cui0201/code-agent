package com.acme.order;

/**
 * 订单领域事件发布器。
 *
 * <p>真实项目里由消息队列实现，这里只保留接口以体现调用关系。
 */
public interface OrderEventPublisher {

    /** 发布订单创建事件，下游履约、营销、对账系统会消费该事件。 */
    void publishOrderCreated(Order order);
}
