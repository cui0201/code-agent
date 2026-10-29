package com.example.codeagent.retrieval.keyword;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ScoredChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手写的内存 BM25 索引。
 *
 * <p>为什么手写而不是用外部分词/检索库：BM25 的公式本身很短，
 * 手写一遍才能真正理解"词频饱和"与"长度归一化"这两件事，
 * 也才能在排查"某条该命中的查询为什么没命中"时有据可依。
 *
 * <p>评分公式：
 * <pre>
 * IDF(t)  = ln(1 + (N - df + 0.5) / (df + 0.5))
 * score    = Σ IDF(t) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * |d| / avgdl))
 * </pre>
 *
 * <p>其中 k1 控制词频饱和速度，b 控制长度归一化强度，取值采用业界常用默认值。
 */
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** 词元到倒排链的映射：每个词元记录它出现在哪些切块中、各出现几次。 */
    private final Map<String, List<Posting>> postings = new HashMap<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private double averageDocLength = 1.0;

    /** 倒排链上的一项：切块下标与词频。 */
    private record Posting(int docIndex, int termFrequency) {
    }

    /** 从切块列表构建索引。 */
    public static Bm25Index build(List<Chunk> sourceChunks) {
        Bm25Index index = new Bm25Index();
        index.addAll(sourceChunks);
        return index;
    }

    public void addAll(List<Chunk> newChunks) {
        for (Chunk chunk : newChunks) {
            int docIndex = chunks.size();
            chunks.add(chunk);

            List<String> tokens = Tokenizer.tokenize(embeddingText(chunk));
            docLengths.add(tokens.size());

            Map<String, Integer> termFrequencies = new LinkedHashMap<>();
            for (String token : tokens) {
                termFrequencies.merge(token, 1, Integer::sum);
            }
            termFrequencies.forEach((term, frequency) ->
                    postings.computeIfAbsent(term, key -> new ArrayList<>())
                            .add(new Posting(docIndex, frequency)));
        }
        recomputeAverageLength();
    }

    /**
     * 参与检索的文本 = 元数据中的符号信息 + 代码内容。
     *
     * <p>把 fqn、方法签名、注解一并纳入，是为了让"类名/方法名"这类查询
     * 能直接命中对应切块，而不是依赖向量语义去猜。
     */
    private static String embeddingText(Chunk chunk) {
        StringBuilder builder = new StringBuilder();
        builder.append(chunk.path()).append(' ');
        Object fqn = chunk.metadata().get("fqn");
        if (fqn != null) {
            builder.append(fqn).append(' ');
        }
        Object signature = chunk.metadata().get("signature");
        if (signature != null) {
            builder.append(signature).append(' ');
        }
        Object annotations = chunk.metadata().get("annotations");
        if (annotations != null) {
            builder.append(annotations).append(' ');
        }
        builder.append(chunk.content());
        return builder.toString();
    }

    /**
     * BM25 检索。
     *
     * @param query 查询文本
     * @param topK  返回条数上限
     * @return 按 BM25 分数降序的结果；无匹配时返回空列表
     */
    public List<ScoredChunk> search(String query, int topK) {
        if (chunks.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<String> terms = Tokenizer.tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        Map<Integer, Double> scores = new HashMap<>();
        for (String term : new java.util.LinkedHashSet<>(terms)) {
            List<Posting> chain = postings.get(term);
            if (chain == null || chain.isEmpty()) {
                continue;
            }
            double idf = idf(chain.size());
            for (Posting posting : chain) {
                double frequency = posting.termFrequency();
                double lengthNorm = 1 - B + B * docLengths.get(posting.docIndex()) / averageDocLength;
                double contribution = idf * frequency * (K1 + 1) / (frequency + K1 * lengthNorm);
                scores.merge(posting.docIndex(), contribution, Double::sum);
            }
        }
        if (scores.isEmpty()) {
            return List.of();
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(topK)
                .map(entry -> new ScoredChunk(chunks.get(entry.getKey()), entry.getValue(), "sparse"))
                .toList();
    }

    private double idf(int documentFrequency) {
        return Math.log(1 + (chunks.size() - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }

    private void recomputeAverageLength() {
        averageDocLength = docLengths.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(1.0);
        if (averageDocLength <= 0) {
            averageDocLength = 1.0;
        }
    }

    public int size() {
        return chunks.size();
    }

    public int termCount() {
        return postings.size();
    }

    /** 判断某个词元是否存在于索引词表中。 */
    public boolean containsTerm(String term) {
        return term != null && postings.containsKey(term.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 某个词元出现在多少个切块里（文档频率）。
     *
     * <p>它是判断"这个词稀有还是常见"的依据：出现在极少数切块里的词
     * 一旦命中，就是强信号；而"使用""环境"这类词命中什么都说明不了。
     *
     * @return 文档频率；词元不存在时返回 0
     */
    public int documentFrequency(String term) {
        if (term == null) {
            return 0;
        }
        List<Posting> chain = postings.get(term.toLowerCase(java.util.Locale.ROOT));
        return chain == null ? 0 : chain.size();
    }

    /** 语料规模（切块数），用于把文档频率换算成比例。 */
    public int corpusSize() {
        return chunks.size();
    }

    public void clear() {
        postings.clear();
        chunks.clear();
        docLengths.clear();
        averageDocLength = 1.0;
    }
}
