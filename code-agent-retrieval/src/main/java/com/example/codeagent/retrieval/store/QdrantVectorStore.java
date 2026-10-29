package com.example.codeagent.retrieval.store;

import com.example.codeagent.core.exception.IndexingException;
import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 Qdrant 的向量存储实现，替换 P0 的内存实现。
 *
 * <p>换它是为了两件事：<b>持久化</b>（进程重启不必重新索引）与
 * <b>近似最近邻检索</b>（HNSW，把 O(N) 暴力扫描降到近似 O(log N)）。
 * 对上层而言接口没有任何变化——这正是 {@link VectorStore} 端口存在的意义。
 *
 * <p>交互方式选择 REST 而不是 gRPC 客户端：本地单机场景的检索 QPS 很低，
 * 用 JDK 内置的 {@link HttpClient} 直接调 REST 接口，可以少引入
 * gRPC / protobuf 一整套依赖，出问题时用 curl 就能复现，排查成本最低。
 *
 * <p>距离度量固定为 Cosine：向量已在嵌入阶段做过 L2 归一化，
 * 因此余弦相似度与点积等价，返回的 score 可直接与本地实现对比。
 */
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String collection;
    private final int dimension;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QdrantVectorStore(String baseUrl, String collection, int dimension) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.collection = collection;
        this.dimension = dimension;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 幂等创建集合：已存在则直接返回。 */
    public void ensureCollection() {
        HttpResponse<String> existing = send(request(collectionsPath()).GET().build());
        if (existing.statusCode() == 200) {
            log.info("Qdrant 集合已存在: {}", collection);
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        vectors.put("size", dimension);
        vectors.put("distance", "Cosine");
        HttpResponse<String> created = send(request(collectionsPath())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
        if (created.statusCode() >= 300) {
            throw new IndexingException("创建 Qdrant 集合失败: HTTP " + created.statusCode()
                    + " " + created.body());
        }
        log.info("已创建 Qdrant 集合: {} (维度={}, 距离=Cosine)", collection, dimension);
    }

    @Override
    public void add(List<Chunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException(
                    "切块数与向量数不一致: " + chunks.size() + " vs " + vectors.size());
        }
        if (chunks.isEmpty()) {
            return;
        }
        // 分批写入，避免一次请求体过大
        int batchSize = 128;
        for (int from = 0; from < chunks.size(); from += batchSize) {
            int to = Math.min(from + batchSize, chunks.size());
            upsertBatch(chunks.subList(from, to), vectors.subList(from, to));
        }
    }

    private void upsertBatch(List<Chunk> chunks, List<float[]> vectors) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode points = body.putArray("points");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            ObjectNode point = points.addObject();
            point.put("id", pointId(chunk.id()).toString());
            ArrayNode vector = point.putArray("vector");
            for (float value : vectors.get(i)) {
                vector.add(value);
            }
            point.set("payload", toPayload(chunk));
        }
        HttpResponse<String> response = send(request(collectionsPath() + "/points?wait=true")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
        if (response.statusCode() >= 300) {
            throw new IndexingException("写入 Qdrant 失败: HTTP " + response.statusCode()
                    + " " + response.body());
        }
    }

    @Override
    public List<ScoredChunk> search(float[] queryVector, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode vector = body.putArray("vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        body.put("limit", topK);
        body.put("with_payload", true);

        HttpResponse<String> response = send(request(collectionsPath() + "/points/search")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
        if (response.statusCode() >= 300) {
            throw new IndexingException("检索 Qdrant 失败: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        return parseSearchResponse(response.body());
    }

    @Override
    public int size() {
        HttpResponse<String> response = send(request(collectionsPath()).GET().build());
        if (response.statusCode() != 200) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("result").path("points_count").asInt(0);
        } catch (IOException e) {
            throw new IndexingException("解析 Qdrant 集合信息失败", e);
        }
    }

    @Override
    public void clear() {
        send(request(collectionsPath()).DELETE().build());
        ensureCollection();
        log.info("已清空并重建 Qdrant 集合: {}", collection);
    }

    /** 探测 Qdrant 是否可用，供测试与启动自检使用。 */
    public boolean isReachable() {
        try {
            HttpResponse<String> response = send(request("/collections").GET().build());
            if (response.statusCode() != 200) {
                log.warn("Qdrant 返回异常状态: HTTP {} ({})", response.statusCode(), baseUrl);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // 把原因打出来：连接失败最常见的原因是服务没起来（进程退出或被容器回收），
            // 其次才是地址/端口配错。原来只返回 false 而不记录原因，
            // 会让"服务没启动"和"配置写错"这两种情况难以区分。
            log.warn("无法连接 Qdrant ({}): {}", baseUrl, e.getMessage());
            return false;
        }
    }

    private List<ScoredChunk> parseSearchResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("result");
            if (!results.isArray()) {
                return List.of();
            }
            List<ScoredChunk> hits = new ArrayList<>(results.size());
            for (JsonNode item : results) {
                Chunk chunk = fromPayload(item.path("payload"));
                hits.add(new ScoredChunk(chunk, item.path("score").asDouble(), "dense"));
            }
            return List.copyOf(hits);
        } catch (IOException e) {
            throw new IndexingException("解析 Qdrant 检索结果失败", e);
        }
    }

    private ObjectNode toPayload(Chunk chunk) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chunkId", chunk.id());
        payload.put("path", chunk.path());
        payload.put("kind", chunk.kind().name());
        payload.put("content", chunk.content());
        payload.put("startLine", chunk.startLine());
        payload.put("endLine", chunk.endLine());
        ObjectNode metadata = payload.putObject("metadata");
        chunk.metadata().forEach((key, value) -> metadata.set(key, objectMapper.valueToTree(value)));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Chunk fromPayload(JsonNode payload) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        JsonNode rawMetadata = payload.path("metadata");
        if (rawMetadata.isObject()) {
            rawMetadata.fields().forEachRemaining(entry ->
                    metadata.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class)));
        }
        return new Chunk(
                payload.path("chunkId").asText(),
                payload.path("path").asText(),
                ChunkKind.valueOf(payload.path("kind").asText("BLOCK")),
                payload.path("content").asText(),
                payload.path("startLine").asInt(1),
                payload.path("endLine").asInt(1),
                metadata);
    }

    /**
     * 把字符串 id 映射成确定性 UUID。
     *
     * <p>Qdrant 的 point id 只接受整数或 UUID，而我们的切块 id 是
     * {@code 全限定名#签名} 形式。用 MD5 派生 UUID 的好处是：同一个切块
     * 每次写入得到相同的 point id，重复索引会覆盖而不是产生重复点。
     */
    static UUID pointId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IndexingException("访问 Qdrant 失败: " + baseUrl + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IndexingException("访问 Qdrant 被中断", e);
        }
    }

    private String collectionsPath() {
        return "/collections/" + collection;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
