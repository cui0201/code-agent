package com.example.codeagent.indexer.snapshot;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.indexer.IncrementalIndexCache;
import com.example.codeagent.indexer.graph.EdgeType;
import com.example.codeagent.indexer.graph.SymbolGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IndexSnapshotStoreTest {

    @TempDir
    Path directory;

    private static Chunk chunk() {
        return new Chunk("com.acme.OrderService", "src/OrderService.java",
                ChunkKind.CLASS, "public class OrderService {}", 10, 20,
                Map.of("fqn", "com.acme.OrderService", "language", "java"));
    }

    private static SymbolGraph graph() {
        SymbolGraph graph = new SymbolGraph();
        graph.addVertex("com.acme.OrderController");
        graph.addEdge("com.acme.OrderController#create", "com.acme.OrderService#createOrder",
                EdgeType.CALLS, "src/OrderController.java", 42);
        graph.addSymbolFile("com.acme.OrderController#create", "src/OrderController.java");
        return graph;
    }

    private static IndexReport report() {
        return new IndexReport(1, 1, 0, 0, 1, "fingerprint-abc", 12, List.of());
    }

    @Test
    @DisplayName("快照能完整往返：切块、元数据、向量、符号图")
    void should_round_trip() {
        IndexSnapshotStore store = new IndexSnapshotStore(directory);
        IncrementalIndexCache.CachedFileView view = new IncrementalIndexCache.CachedFileView(
                List.of(chunk()), List.of(new float[]{0.25f, -0.5f, 0.75f}));

        store.save(report(), Map.of("src/OrderService.java", "hash-1"),
                Map.of("src/OrderService.java", view), graph());
        IndexSnapshotStore.Snapshot snapshot = store.load().orElseThrow();

        assertThat(snapshot.corpusFingerprint()).isEqualTo("fingerprint-abc");
        assertThat(snapshot.fileHashes()).containsEntry("src/OrderService.java", "hash-1");

        IncrementalIndexCache.CachedFileView restored = snapshot.cached().get("src/OrderService.java");
        assertThat(restored.chunks()).hasSize(1);
        Chunk restoredChunk = restored.chunks().get(0);
        assertThat(restoredChunk.id()).isEqualTo("com.acme.OrderService");
        assertThat(restoredChunk.startLine()).isEqualTo(10);
        assertThat(restoredChunk.metadata()).containsEntry("fqn", "com.acme.OrderService");
        assertThat(restored.vectors().get(0))
                .containsExactly(0.25f, -0.5f, 0.75f);

        assertThat(snapshot.graph().symbolCount()).isEqualTo(3);
        assertThat(snapshot.graph().edgeCount()).isEqualTo(1);
        assertThat(snapshot.graph().fileOf("com.acme.OrderController#create"))
                .isEqualTo("src/OrderController.java");
    }

    @Test
    @DisplayName("向量用 base64 存，解码后数值完全一致")
    void should_encode_vectors_losslessly() {
        float[] vector = {0.1f, 1e-8f, -12345.678f, 0f};

        float[] decoded = IndexSnapshotStore.decodeVector(
                IndexSnapshotStore.encodeVector(vector));

        assertThat(decoded).containsExactly(vector);
        assertThat(decoded[1]).isCloseTo(1e-8f, within(1e-12f));
    }

    @Test
    @DisplayName("快照损坏时返回空而不是抛异常")
    void should_ignore_corrupted_snapshot() throws IOException {
        IndexSnapshotStore store = new IndexSnapshotStore(directory);
        Files.createDirectories(directory);
        Files.writeString(store.file(), "{ 这不是合法 JSON");

        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("版本不匹配时忽略快照")
    void should_ignore_unknown_version() throws IOException {
        IndexSnapshotStore store = new IndexSnapshotStore(directory);
        Files.createDirectories(directory);
        Files.writeString(store.file(), "{\"version\": 999, \"files\": []}");

        assertThat(store.load()).isEmpty();
    }
}
