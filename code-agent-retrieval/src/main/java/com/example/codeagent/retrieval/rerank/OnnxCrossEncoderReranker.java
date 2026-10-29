package com.example.codeagent.retrieval.rerank;

import com.example.codeagent.core.exception.IndexingException;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.Reranker;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 ONNX 交叉编码器的重排器，模型在 JVM 进程内运行。
 *
 * <p>使用方式：把候选块的内容与问题一起交给模型打分，按分数降序返回。
 * 模型文件需要单独下载（体积较大，不放进代码仓库），
 * 见 {@code scripts/download-reranker-model.sh}。
 *
 * <p>两个实现细节值得注意：
 * <ol>
 *   <li><b>批量打分</b>：{@code scoreAll} 一次传入全部候选，比逐个调用快得多，
 *       这是重排能在延迟预算内完成的关键；</li>
 *   <li><b>截断候选文本</b>：交叉编码器的输入长度有限（通常 512 token），
 *       过长的代码块会被截断，所以这里主动限制每个候选参与打分的字符数，
 *       避免把大量无关代码喂进去稀释查询信号。</li>
 * </ol>
 */
public class OnnxCrossEncoderReranker implements Reranker, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxCrossEncoderReranker.class);

    /** 单个候选参与打分的最大字符数，防止超长代码块挤占模型输入。 */
    private static final int MAX_DOCUMENT_CHARS = 2000;

    private final ScoringModel scoringModel;
    private final String modelName;

    public OnnxCrossEncoderReranker(String modelPath, String tokenizerPath, String modelName) {
        requireReadable(modelPath, "重排模型");
        requireReadable(tokenizerPath, "分词器文件");
        this.scoringModel = new OnnxScoringModel(modelPath, tokenizerPath);
        this.modelName = modelName;
        log.info("重排模型已加载: {}", modelName);
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<TextSegment> segments = candidates.stream()
                .map(candidate -> TextSegment.from(truncate(candidate.chunk().content())))
                .toList();

        final Response<List<Double>> response;
        try {
            response = scoringModel.scoreAll(segments, query);
        } catch (RuntimeException e) {
            throw new IndexingException("重排失败: " + e.getMessage(), e);
        }
        List<Double> scores = response.content();
        if (scores == null || scores.size() != candidates.size()) {
            throw new IndexingException("重排返回的分数数量与候选数量不一致");
        }

        List<ScoredChunk> reranked = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            reranked.add(new ScoredChunk(candidates.get(i).chunk(), scores.get(i), "rerank"));
        }
        reranked.sort(Comparator.naturalOrder());
        return List.copyOf(reranked.subList(0, Math.min(topK, reranked.size())));
    }

    @Override
    public String modelName() {
        return modelName;
    }

    /** 关闭底层 ONNX 会话，释放原生内存。 */
    @Override
    public void close() {
        if (scoringModel instanceof OnnxScoringModel onnx) {
            onnx.close();
        }
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_DOCUMENT_CHARS
                ? content
                : content.substring(0, MAX_DOCUMENT_CHARS);
    }

    private static void requireReadable(String path, String description) {
        if (path == null || path.isBlank()) {
            throw new IndexingException(description + "路径为空");
        }
        Path resolved = Path.of(path);
        if (!Files.isReadable(resolved)) {
            throw new IndexingException(description + "不可读: " + resolved.toAbsolutePath());
        }
    }
}
