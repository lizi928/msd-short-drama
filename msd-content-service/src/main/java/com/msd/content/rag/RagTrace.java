package com.msd.content.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单次 RAG 请求的追踪上下文。
 *
 * <p>通过 ChatClient advisor param 传入，RetrievalAugmentationAdvisor 会把它复制到
 * Query.context，因此改写器、检索器和精排器读到的是同一个实例。</p>
 */
public class RagTrace {

    /** advisor param 与 Query.context 共用的键。 */
    public static final String CONTEXT_KEY = "msd.rag.trace";

    // 用户原始查询
    private final String originalQuery;
    // 请求开始时间（毫秒时间戳）
    private final long startedAt;

    // 改写后的查询，未改写或失败时为 null
    private String rewrittenQuery;
    // 改写是否失败
    private boolean rewriteFailed;
    // 改写耗时（毫秒）
    private long rewriteMillis;

    // 向量检索命中数
    private int vectorHits;
    // BM25 检索命中数
    private int bm25Hits;
    // RRF 融合后的文档数
    private int rrfHits;
    // 向量检索耗时（毫秒）
    private long vectorMillis;
    // BM25 检索耗时（毫秒）
    private long bm25Millis;

    // Rerank 耗时（毫秒）
    private long rerankMillis;
    // 请求指定的返回条数，0 表示使用配置默认值
    private int requestedTopK;
    // 最终返回给用户的文档
    private List<Document> finalDocuments = List.of();

    // 本次请求发生的降级标记，集合本身线程安全
    private final Set<String> degradedFlags = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * 创建一次请求的追踪对象。
     */
    public RagTrace(String originalQuery) {
        this.originalQuery = originalQuery;
        this.startedAt = System.currentTimeMillis();
    }

    /**
     * 从 Query.context 中取回追踪对象，缺失时返回 null。
     */
    public static RagTrace from(Query query) {
        if (query == null || query.context() == null) {
            return null;
        }
        Object value = query.context().get(CONTEXT_KEY);
        return value instanceof RagTrace trace ? trace : null;
    }

    /**
     * 记录降级标记，重复标记自动去重。
     */
    public void markDegraded(String flag) {
        degradedFlags.add(flag);
    }

    /**
     * 当前请求是否发生过降级。
     */
    public boolean isDegraded() {
        return !degradedFlags.isEmpty();
    }

    /** 用户原始查询。 */
    public String getOriginalQuery() {
        return originalQuery;
    }

    /** 请求开始时间（毫秒时间戳）。 */
    public long getStartedAt() {
        return startedAt;
    }

    /** 改写后的查询，未改写或失败时为 null。 */
    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    /** 记录改写后的查询。 */
    public void setRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    /** 改写是否失败。 */
    public boolean isRewriteFailed() {
        return rewriteFailed;
    }

    /** 记录改写失败状态。 */
    public void setRewriteFailed(boolean rewriteFailed) {
        this.rewriteFailed = rewriteFailed;
    }

    /** 改写耗时（毫秒）。 */
    public long getRewriteMillis() {
        return rewriteMillis;
    }

    /** 记录改写耗时（毫秒）。 */
    public void setRewriteMillis(long rewriteMillis) {
        this.rewriteMillis = rewriteMillis;
    }

    /** 向量检索命中数。 */
    public int getVectorHits() {
        return vectorHits;
    }

    /** 记录向量检索命中数。 */
    public void setVectorHits(int vectorHits) {
        this.vectorHits = vectorHits;
    }

    /** BM25 检索命中数。 */
    public int getBm25Hits() {
        return bm25Hits;
    }

    /** 记录 BM25 检索命中数。 */
    public void setBm25Hits(int bm25Hits) {
        this.bm25Hits = bm25Hits;
    }

    /** RRF 融合后的文档数。 */
    public int getRrfHits() {
        return rrfHits;
    }

    /** 记录 RRF 融合后的文档数。 */
    public void setRrfHits(int rrfHits) {
        this.rrfHits = rrfHits;
    }

    /** 向量检索耗时（毫秒）。 */
    public long getVectorMillis() {
        return vectorMillis;
    }

    /** 记录向量检索耗时（毫秒）。 */
    public void setVectorMillis(long vectorMillis) {
        this.vectorMillis = vectorMillis;
    }

    /** BM25 检索耗时（毫秒）。 */
    public long getBm25Millis() {
        return bm25Millis;
    }

    /** 记录 BM25 检索耗时（毫秒）。 */
    public void setBm25Millis(long bm25Millis) {
        this.bm25Millis = bm25Millis;
    }

    /** Rerank 耗时（毫秒）。 */
    public long getRerankMillis() {
        return rerankMillis;
    }

    /** 记录 Rerank 耗时（毫秒）。 */
    public void setRerankMillis(long rerankMillis) {
        this.rerankMillis = rerankMillis;
    }

    /** 请求指定的返回条数，0 表示使用配置默认值。 */
    public int getRequestedTopK() {
        return requestedTopK;
    }

    /** 记录请求指定的返回条数。 */
    public void setRequestedTopK(int requestedTopK) {
        this.requestedTopK = requestedTopK;
    }

    /** 最终返回给用户的文档。 */
    public List<Document> getFinalDocuments() {
        return finalDocuments;
    }

    /** 记录最终文档，null 转为空列表并做不可变拷贝。 */
    public void setFinalDocuments(List<Document> finalDocuments) {
        this.finalDocuments = finalDocuments == null ? List.of() : List.copyOf(finalDocuments);
    }

    /**
     * 复制降级标记，避免调用方修改内部集合。
     */
    public List<String> getDegradedFlags() {
        synchronized (degradedFlags) {
            return List.copyOf(degradedFlags);
        }
    }
}
