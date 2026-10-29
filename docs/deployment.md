# 部署指南

> 适用版本：v1.0（P0–P9 完成后）
> 目标：把 code-agent 部署到一台机器上，指向你的 Java 工程，能通过 HTTP / MCP 提问。

---

## 0. 部署前检查

| 项 | 要求 | 说明 |
| --- | --- | --- |
| Docker | Engine 24+ / Compose v2 | 一把梭部署只需要这个 |
| 内存 | ≥ 4 GB（推荐 8 GB） | 应用 + Qdrant + 本地 ONNX 模型 |
| 磁盘 | ≥ 2 GB | 镜像约 500 MB，索引快照约 6 MB / 1300 块 |
| 大模型 API | 一个 OpenAI 兼容端点 + Key | DeepSeek / 通义千问 / 智谱 / Kimi / OpenAI 均可 |
| 被索引工程 | 一个 Java 工程目录 | 以**只读**方式挂载，不会被修改 |

> 嵌入模型随应用打包在 jar 内（约 24 MB），**不需要**额外下载模型、也不需要 GPU。

---

## 1. 最短路径：Docker Compose（推荐）

### 1.1 准备

```bash
# 把项目放到部署机
cd /opt/code-agent

# 唯一必须的凭据：只放环境变量，不要写进任何文件
export LLM_API_KEY=sk-xxxxxxxx

# 要索引的工程（只读挂载）
export JAVA_PROJECT=/srv/my-java-project
```

可选覆盖项：

```bash
export LLM_BASE_URL=https://api.deepseek.com   # 默认值
export LLM_MODEL=deepseek-flash                # 默认值
```

### 1.2 启动

```bash
docker compose up -d --build
```

首次构建会在容器内执行 Maven 打包，需要拉取依赖，视网络情况约 5~20 分钟；
之后的启动都是秒级。

### 1.3 等待就绪

```bash
docker compose ps          # 等到 code-agent 显示 healthy
docker compose logs -f code-agent
```

日志里应当依次出现：

```
启动自动索引: /workspace
扫描完成: 文件=..., 切块=...
索引完成: ...
启动索引完成: ...
```

> 首次启动会建索引（大工程可能几十秒到几分钟）。
> 之后容器重启会加载 `.codeagent` 卷里的快照，只需几百毫秒。

### 1.4 验证

```bash
# 健康检查：未建好索引时返回 503，就绪后返回 200 + "status":"ready"
curl -s localhost:8080/v1/health | jq

# 问一个配置类问题（答案应在 application.yml 里）
curl -s -X POST localhost:8080/v1/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"默认的拒答阈值是多少？"}' | jq '{refused, citations, answer}'

# 影响面分析
curl -s "localhost:8080/v1/impact?symbol=OrderService%23createOrder&depth=2" | jq

# 运行指标
curl -s localhost:8080/v1/metrics | jq
```

### 1.5 停止与清理

```bash
docker compose down            # 停止，保留索引快照与向量数据
docker compose down -v         # 连数据卷一起删（下次会全量重建索引）
```

---

## 2. 本地部署（不用 Docker）

### 2.1 环境

- JDK 21（Apple Silicon 必须是 **arm64** 版本，x86_64 在 Rosetta 下加载不了 ONNX 原生库）
- Maven 3.9+

### 2.2 构建与启动

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
mvn -B -DskipTests package

export LLM_API_KEY=sk-xxxxxxxx
java -jar code-agent-web/target/code-agent-web-0.1.0-SNAPSHOT.jar \
  --code-agent.index.root=/path/to/your/project
```

应用默认使用**进程内向量存储**，不需要 Qdrant。想用 Qdrant：

```bash
docker run -d --name code-agent-qdrant -p 6333:6333 qdrant/qdrant
java -jar code-agent-web/target/code-agent-web-0.1.0-SNAPSHOT.jar \
  --code-agent.index.root=/path/to/your/project \
  --code-agent.store.type=qdrant
```

### 2.3 用 systemd 托管（Linux）

```ini
# /etc/systemd/system/code-agent.service
[Unit]
Description=code-agent
After=network.target docker.service

[Service]
Type=simple
User=codeagent
WorkingDirectory=/opt/code-agent
Environment="LLM_API_KEY=sk-xxxxxxxx"
Environment="JAVA_HOME=/usr/lib/jvm/temurin-21-jdk"
ExecStart=/usr/lib/jvm/temurin-21-jdk/bin/java -jar /opt/code-agent/code-agent-web/target/code-agent-web-0.1.0-SNAPSHOT.jar --code-agent.index.root=/srv/my-java-project
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now code-agent
```

> 生产环境不要把 Key 直接写在 unit 文件里，用 `EnvironmentFile` 指向一个 `chmod 600` 的文件。

---

## 3. 关键配置

| 环境变量 / 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `LLM_API_KEY` | 无（必须） | 大模型凭据，只从环境变量读取 |
| `LLM_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容端点 |
| `LLM_MODEL` | `deepseek-flash` | 模型名 |
| `--code-agent.index.root` | `.` | 被索引工程根目录 |
| `--code-agent.index.auto-index` | `true` | 启动时自动建索引 |
| `--code-agent.index.snapshot-dir` | `.codeagent/index` | 索引快照目录 |
| `--code-agent.store.type` | `memory` | `memory` 或 `qdrant` |
| `--code-agent.retrieval.strategy` | `hybrid` | `hybrid` / `vector` / `keyword` |
| `--code-agent.cache.enabled` | `true` | 语义缓存 |
| `--code-agent.guard.relevance-judge` | `true` | LLM 相关性判官（拒答判据） |
| `--code-agent.rerank.enabled` | `false` | 重排（实测无收益，默认关闭） |

---

## 4. 验证清单（部署完按顺序过一遍）

- [ ] `GET /v1/health` 返回 200 且 `"status":"ready"`、`chunks > 0`
- [ ] 问一个配置类问题（如"默认的拒答阈值是多少"）能答出 `0.62` 并带引用
- [ ] 问一个库里没有的问题（如"项目有多少 star"）会被拒答（`refused=true`）
- [ ] `GET /v1/impact?symbol=<你的类>%23<方法>` 能列出调用方
- [ ] `GET /v1/metrics` 能读到缓存命中率与延迟分位数
- [ ] MCP 客户端（可选）：`bash scripts/mcp-smoke-test.sh` 通过

---

## 5. 日常运维

### 更新被索引的代码

直接替换/更新源码目录，然后重启应用（或调用 `POST /v1/index`）：

```bash
docker compose restart code-agent
```

增量索引按**文件内容哈希**判断，只有变了的文件会重新切块与嵌入；
其余文件从快照复用。

### 备份

需要备份的只有两样：

- 索引快照卷（`code-agent-index`）——删了会全量重建，不影响正确性；
- Qdrant 数据卷（`qdrant-data`）——如果用了 Qdrant。

> 快照里含**代码原文**，备份文件应按源码同等敏感级别管理。

### 日志与指标

- 应用日志：`docker compose logs -f code-agent`
- 指标：`GET /v1/metrics`（缓存命中率、拒答率、检索/生成 P50/P95）
- 生产环境建议把指标接 Prometheus（项目内的收集器接口与 Micrometer 对齐，替换成本低）

---

## 6. 安全注意事项

1. **API Key 只走环境变量或 secret**，不要提交、不要写进镜像层。
2. **源码只读挂载**：compose 里 `/workspace:ro`，应用不会写你的代码。
3. **快照含代码原文**：`.codeagent/` 权限应与代码一致，且已在 `.gitignore` 中。
4. **服务本身没有认证**：默认只应监听内网/本机；对外暴露请在前面加反向代理（Nginx / Caddy）并启用鉴权与 TLS。
5. **发送给外部 LLM 的内容**只有检索命中的片段与问题，不是整个代码库；如需更严格，可在出口加脱敏。

---

## 7. 故障排查

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 启动报 `LLM_API_KEY` 缺失 | compose 的必填校验生效 | `export LLM_API_KEY=...` 后重启 |
| `health` 持续 503 | 索引还没完成 | 看日志里的"索引完成"；大工程首次会慢 |
| `Failed to load Huggingface native library` | JDK 架构不对（x86_64 跑在 arm64 上） | 换原生 arm64 JDK 21 |
| `无法连接 Qdrant` | 容器没起 / 地址写错 | `docker compose ps`；确认 `qdrant-url` |
| LLM 返回 401 | Key 无效或 base-url 不匹配 | 用 `curl $LLM_BASE_URL/models` 验证 |
| 回答总是"未找到依据" | 索引目录配错（指向了空目录） | 看日志"扫描完成: 文件=N"；确认 `index.root` |
| 首次索引很慢 | 嵌入是 CPU 密集的（约 10 ms/块） | 属正常；重启后走快照，秒级 |
| 端口 8080 被占用 | 已有实例在跑 | `lsof -i :8080`，或改 compose 的端口映射 |
| MCP 客户端连不上 | stdout 被日志污染 | 只能用 `--mcp` 模式启动；日志已自动改道 stderr |

---

## 8. 已知边界

- **单机单工程**：一个实例只索引一个工程目录；多项目请起多个实例（不同端口/容器）。
- **无认证、无多租户**：见安全注意事项第 4 条。
- **文档通道**覆盖 Javadoc / OpenAPI / Markdown；AsciiDoc、Confluence 尚未接入。
- **重排默认关闭**：实测在中文提问 + Java 代码场景下没有质量收益且延迟高。
