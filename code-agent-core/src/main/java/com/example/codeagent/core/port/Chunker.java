package com.example.codeagent.core.port;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.SourceFile;

import java.util.List;

/**
 * 切块器：把一个源文件切成若干带行号的切块。
 *
 * <p>实现必须保证：
 * <ol>
 *   <li>输出切块的 content 与源文件对应行区间一致；</li>
 *   <li>行号连续且不越界；</li>
 *   <li>对同一输入的输出是确定的，便于评测复现。</li>
 * </ol>
 *
 * <p>P0 实现为 LineWindowChunker；P1 实现为 JavaAstChunker。
 */
public interface Chunker {

    /**
     * @param file 源文件
     * @return 切块列表；文件无有效内容时返回空列表
     */
    List<Chunk> chunk(SourceFile file);
}
