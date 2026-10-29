package com.example.codeagent.indexer.snapshot;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.indexer.IncrementalIndexCache;
import com.example.codeagent.indexer.graph.EdgeType;
import com.example.codeagent.indexer.graph.SymbolGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 索引快照：把切块、向量与符号图落盘，重启后不必重新嵌入、重新解析。
 *
 * <h2>为什么需要它</h2>
 *
 * 1362 个切块的一次全量索引约 20 秒，其中绝大部分是嵌入（约 45 ms/块）
 * 与 Spoon 解析（约 70 ms/文件）。重启一次就重来一遍，对日常使用来说很难接受。
 *
 * <h2>存什么、不存什么</h2>
 *
 * <ul>
 *   <li><b>存</b>：切块（含元数据）、向量、符号图、每个文件的内容哈希。
 *       它们正好是"重算最贵"的部分；</li>
 *   <li><b>不存</b>：BM25 索引。它只是切块内容的倒排表，
 *       从切块重建只要一两秒，而序列化它会让快照大好几倍、格式也更脆弱。</li>
 * </ul>
 *
 * <h2>为什么用 base64 存向量</h2>
 *
 * JSON 里直接写 512 个浮点数会膨胀成上万个字符；把 float[] 按小端字节序
 * 转成字节再 base64，体积约为原二进制的一点三倍，且跨平台稳定。
 *
 * <h2>写入的原子性</h2>
 *
 * 先写临时文件再整体替换：索引过程中断电或崩溃时，旧快照仍然可用，
 * 不会出现"快照只写了一半、下次启动直接解析失败"的情况。
 */
public class IndexSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(IndexSnapshotStore.class);

    /** 快照格式版本。读取到不认识的版本时直接忽略，而不是猜测字段含义。 */
    private static final int FORMAT_VERSION = 1;

    private static final String FILE_NAME = "index-snapshot.json";

    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public IndexSnapshotStore(Path directory) {
        this.directory = directory;
    }

    /** 快照文件路径。 */
    public Path file() {
        return directory.resolve(FILE_NAME);
    }

    public boolean exists() {
        return Files.isRegularFile(file());
    }

    /**
     * 保存快照。
     *
     * @param report 本次索引报告（取语料指纹）
     * @param files  文件路径到内容哈希
     * @param cached 每个文件的切块与向量
     * @param graph  符号图
     */
    public void save(IndexReport report,
                     Map<String, String> files,
                     Map<String, IncrementalIndexCache.CachedFileView> cached,
                     SymbolGraph graph) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("corpusFingerprint", report.corpusFingerprint());
        root.put("savedAt", System.currentTimeMillis());

        ArrayNode fileArray = root.putArray("files");
        cached.forEach((path, view) -> {
            ObjectNode fileNode = fileArray.addObject();
            fileNode.put("path", path);
            fileNode.put("hash", files.getOrDefault(path, ""));
            ArrayNode chunkArray = fileNode.putArray("chunks");
            for (Chunk chunk : view.chunks()) {
                ObjectNode chunkNode = chunkArray.addObject();
                chunkNode.put("id", chunk.id());
                chunkNode.put("path", chunk.path());
                chunkNode.put("kind", chunk.kind().name());
                chunkNode.put("content", chunk.content());
                chunkNode.put("startLine", chunk.startLine());
                chunkNode.put("endLine", chunk.endLine());
                chunkNode.set("metadata", mapper.valueToTree(chunk.metadata()));
            }
            ArrayNode vectorArray = fileNode.putArray("vectors");
            for (float[] vector : view.vectors()) {
                vectorArray.add(encodeVector(vector));
            }
        });

        ObjectNode graphNode = root.putObject("graph");
        ArrayNode nodeArray = graphNode.putArray("nodes");
        graph.symbols().forEach(nodeArray::add);
        ObjectNode filesNode = graphNode.putObject("symbolFiles");
        graph.symbolFiles().forEach(filesNode::put);
        ArrayNode edgeArray = graphNode.putArray("edges");
        for (SymbolGraph.EdgeView edge : graph.edges()) {
            ObjectNode edgeNode = edgeArray.addObject();
            edgeNode.put("from", edge.from());
            edgeNode.put("to", edge.to());
            edgeNode.put("type", edge.type().name());
            edgeNode.put("file", edge.file());
            edgeNode.put("line", edge.line());
        }

        try {
            Files.createDirectories(directory);
            Path temp = directory.resolve(FILE_NAME + ".tmp");
            Files.writeString(temp, mapper.writeValueAsString(root));
            Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.info("索引快照已保存: {} (文件={}, 大小={} KB)",
                    file(), cached.size(), Files.size(file()) / 1024);
        } catch (IOException e) {
            // 快照是纯优化：写失败不影响本次索引结果，只意味着下次重启要重建
            log.warn("保存索引快照失败（不影响本次索引）: {}", e.getMessage());
        }
    }

    /**
     * 读取快照。
     *
     * @return 快照；文件不存在、版本不匹配或解析失败时返回空
     */
    public Optional<Snapshot> load() {
        if (!exists()) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file()));
            if (root.path("version").asInt(-1) != FORMAT_VERSION) {
                log.warn("索引快照版本不匹配，忽略: {}", root.path("version"));
                return Optional.empty();
            }
            Map<String, String> files = new LinkedHashMap<>();
            Map<String, IncrementalIndexCache.CachedFileView> cached = new LinkedHashMap<>();
            for (JsonNode fileNode : root.path("files")) {
                String path = fileNode.path("path").asText();
                files.put(path, fileNode.path("hash").asText());
                List<Chunk> chunks = new ArrayList<>();
                for (JsonNode chunkNode : fileNode.path("chunks")) {
                    chunks.add(new Chunk(
                            chunkNode.path("id").asText(),
                            chunkNode.path("path").asText(),
                            ChunkKind.valueOf(chunkNode.path("kind").asText("BLOCK")),
                            chunkNode.path("content").asText(),
                            chunkNode.path("startLine").asInt(1),
                            chunkNode.path("endLine").asInt(1),
                            mapper.convertValue(chunkNode.path("metadata"), Map.class)));
                }
                List<float[]> vectors = new ArrayList<>();
                for (JsonNode vectorNode : fileNode.path("vectors")) {
                    vectors.add(decodeVector(vectorNode.asText()));
                }
                cached.put(path, new IncrementalIndexCache.CachedFileView(chunks, vectors));
            }

            SymbolGraph graph = new SymbolGraph();
            JsonNode graphNode = root.path("graph");
            for (JsonNode node : graphNode.path("nodes")) {
                graph.addVertex(node.asText());
            }
            graphNode.path("symbolFiles").fields().forEachRemaining(entry ->
                    graph.addSymbolFile(entry.getKey(), entry.getValue().asText()));
            for (JsonNode edge : graphNode.path("edges")) {
                graph.addEdge(edge.path("from").asText(), edge.path("to").asText(),
                        EdgeType.valueOf(edge.path("type").asText()),
                        edge.path("file").asText(), edge.path("line").asInt(-1));
            }
            log.info("索引快照已加载: 文件={}, 符号={}, 边={}",
                    files.size(), graph.symbolCount(), graph.edgeCount());
            return Optional.of(new Snapshot(root.path("corpusFingerprint").asText(),
                    files, cached, graph));
        } catch (IOException | RuntimeException e) {
            // 快照损坏时静默降级为全量重建，绝不能因为缓存坏了让服务起不来
            log.warn("读取索引快照失败，将执行全量索引: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 一份已加载的快照。 */
    public record Snapshot(String corpusFingerprint,
                           Map<String, String> fileHashes,
                           Map<String, IncrementalIndexCache.CachedFileView> cached,
                           SymbolGraph graph) {
    }

    /** float[] → 小端字节 → base64。 */
    static String encodeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /** base64 → 小端字节 → float[]。 */
    static float[] decodeVector(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
