# 快速开始

## 下单流程

调用 `OrderController#create` 发起下单，请求体为 `OrderRequest`。

服务端处理链路：

1. `OrderService#createOrder` 校验参数；
2. 按 requestId 做幂等判断；
3. 调用 `RiskClient#allowOrder` 检查风控；
4. 落库并发布订单创建事件。

## 金额规则

金额必须落在闭区间 [0.01, 100000] 元之间，最多保留两位小数。
规则实现在 `OrderService#validateAmount`。

## 订单状态流转

订单创建后状态为 CREATED，支付成功后变为 PAID，取消后变为 CANCELLED。
状态定义见 `Order.OrderStatus`。
