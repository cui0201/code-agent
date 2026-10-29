# 订单 API

本文件由接口文档生成流水线产出，请勿手工修改。

## 创建订单

`POST /api/orders`

### 请求字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| userId | long | 是 | 下单用户 ID |
| skuId | long | 是 | 商品 ID |
| amount | decimal | 是 | 订单金额，单位元，最多两位小数 |
| requestId | string | 是 | 客户端生成的幂等键 |
| couponCode | string | 否 | 优惠券码 |

### 响应字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| orderNo | string | 订单号 |
| status | string | 订单状态，取值 CREATED、PAID、CANCELLED |
| createdAt | timestamp | 创建时间 |

### 错误码

| 错误码 | 含义 |
| --- | --- |
| 400 | 参数校验失败，例如金额超过单笔上限 |
| 429 | 触发风控限制，当日下单次数超限 |

### 相关实现

- 接口层：`com.acme.order.OrderController#create`
- 领域层：`com.acme.order.OrderService#createOrder`
- 金额规则：`OrderService#validateAmount`

## 查询订单

`GET /api/orders/{orderNo}`

按订单号查询订单详情，订单不存在时返回 400。

### 相关实现

- `com.acme.order.OrderController#get`
