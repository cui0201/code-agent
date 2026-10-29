package com.acme.order;

/**
 * 订单 HTTP 接口。
 *
 * <p>只做参数绑定与响应封装，业务规则全部委托给 {@link OrderService}。
 */
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建订单接口。
     *
     * <p>方法：POST {@code /api/orders}
     *
     * <p>请求体字段：userId、skuId、amount、requestId、couponCode。
     * 其中 amount 为订单金额（元），requestId 为客户端生成的幂等键。
     *
     * <p>错误码：
     * <ul>
     *   <li>400 参数校验失败，例如金额超限或幂等键为空；</li>
     *   <li>429 触发风控限制。</li>
     * </ul>
     */
    public Order create(OrderRequest request) {
        return orderService.createOrder(request);
    }

    /**
     * 查询订单接口。
     *
     * <p>方法：GET {@code /api/orders/{orderNo}}
     */
    public Order get(String orderNo) {
        return orderService.getOrder(orderNo);
    }
}
