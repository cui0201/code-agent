package com.acme.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 订单领域服务。
 *
 * <p>负责下单的核心业务逻辑：参数校验、幂等控制、订单落库与领域事件发布。
 * 事务边界由调用方（OrderController）所在的 Spring 事务管理，本类不显式声明事务。
 */
public class OrderService {

    /** 单笔订单金额下限，低于该值视为无效订单。 */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    /** 单笔订单金额上限，超过该值需要走人工审核流程。 */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000");

    /** 单用户单日的下单次数上限，用于风控。 */
    private static final int DAILY_ORDER_LIMIT = 50;

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;
    private final RiskClient riskClient;

    public OrderService(OrderRepository orderRepository,
                        OrderEventPublisher eventPublisher,
                        RiskClient riskClient) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.riskClient = riskClient;
    }

    /**
     * 创建订单。
     *
     * <p>处理流程：
     * <ol>
     *   <li>校验请求参数（金额、商品、用户）；</li>
     *   <li>按 requestId 做幂等判断，已存在则直接返回历史订单；</li>
     *   <li>调用风控检查用户当日下单次数；</li>
     *   <li>生成订单号并落库；</li>
     *   <li>发布订单创建事件，通知下游履约与营销系统。</li>
     * </ol>
     *
     * @param request 下单请求
     * @return 创建成功或已存在的订单
     * @throws IllegalArgumentException 参数校验不通过
     * @throws IllegalStateException    风控拒绝
     */
    public Order createOrder(OrderRequest request) {
        validateRequest(request);

        Optional<Order> existing = orderRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            // 幂等：同一个 requestId 重复提交时返回首次创建的订单，不重复下单
            return existing.get();
        }

        if (!riskClient.allowOrder(request.userId(), DAILY_ORDER_LIMIT)) {
            throw new IllegalStateException("用户当日下单次数已达上限，拒绝下单");
        }

        Order order = new Order(
                generateOrderNo(),
                request.userId(),
                request.amount(),
                Order.OrderStatus.CREATED,
                request.requestId(),
                Instant.now());

        Order saved = orderRepository.save(order);
        eventPublisher.publishOrderCreated(saved);
        return saved;
    }

    /**
     * 校验下单请求。
     *
     * <p>金额相关的三条规则：不能为空、必须大于等于 0.01 元、不得超过 10 万元。
     */
    private void validateRequest(OrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("下单请求不能为空");
        }
        if (request.userId() == null || request.userId() <= 0) {
            throw new IllegalArgumentException("用户 ID 非法");
        }
        if (request.skuId() == null || request.skuId() <= 0) {
            throw new IllegalArgumentException("商品 ID 非法");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new IllegalArgumentException("幂等键 requestId 不能为空");
        }
        validateAmount(request.amount());
    }

    /**
     * 校验订单金额。
     *
     * <p>规则来自资损防控要求：金额必须落在闭区间 [0.01, 100000] 内。
     * 使用 {@code compareTo} 而不是 {@code equals}，因为 BigDecimal 的
     * {@code equals} 会区分精度（1.0 与 1.00 不相等），这在下单场景会造成误判。
     */
    private void validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("订单金额不能为空");
        }
        if (amount.compareTo(MIN_AMOUNT) < 0) {
            throw new IllegalArgumentException("订单金额必须大于等于 " + MIN_AMOUNT + " 元");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("订单金额不得超过 " + MAX_AMOUNT + " 元");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("订单金额最多保留两位小数");
        }
    }

    /** 查询订单，不存在时抛出异常。 */
    public Order getOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
    }

    private String generateOrderNo() {
        return "ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
