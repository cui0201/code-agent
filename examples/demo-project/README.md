# demo-order

一个用于演示的订单服务样例工程。

它同时充当 code-agent 的索引语料：源码用于验证代码通道，
`docs/` 下的文档用于验证文档通道与漂移检测。

## 模块结构

| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/acme/order/OrderController.java` | 订单 HTTP 接口 |
| `src/main/java/com/acme/order/OrderService.java` | 订单领域服务 |
| `src/main/java/com/acme/order/OrderRepository.java` | 订单仓储接口 |
| `src/main/java/com/acme/order/RiskClient.java` | 风控客户端接口 |
| `docs/api/order-api.md` | 接口文档 |
| `docs/adr/` | 架构决策记录 |
| `docs/guide/` | 使用指南 |

## 构建

```bash
mvn clean package
```

生成接口文档：

```bash
mvn javadoc:javadoc
```
