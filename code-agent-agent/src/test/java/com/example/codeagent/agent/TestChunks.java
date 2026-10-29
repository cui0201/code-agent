package com.example.codeagent.agent;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.ScoredChunk;

import java.util.Map;

/** 测试用的切块构造工具。 */
final class TestChunks {

    private TestChunks() {
    }

    static Chunk chunk(String path, int startLine, int endLine) {
        return chunk(path, startLine, endLine, "// " + path + "\nclass Demo {}");
    }

    static Chunk chunk(String path, int startLine, int endLine, String content) {
        return new Chunk(
                path + "#L" + startLine + "-" + endLine,
                path,
                ChunkKind.BLOCK,
                content,
                startLine,
                endLine,
                Map.of("language", "java"));
    }

    static ScoredChunk scored(String path, int startLine, int endLine, double score) {
        return new ScoredChunk(chunk(path, startLine, endLine), score, "dense");
    }

    static ScoredChunk scored(String path, int startLine, int endLine, double score, String content) {
        return new ScoredChunk(chunk(path, startLine, endLine, content), score, "dense");
    }
}
