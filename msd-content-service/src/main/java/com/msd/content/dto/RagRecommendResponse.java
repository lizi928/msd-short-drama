package com.msd.content.dto;

import java.util.List;

/**
 * RAG 推荐响应。
 *
 * @param answer         推荐回答文本；检索为空、超时或生成失败时返回固定兜底文案
 * @param rewrittenQuery 模型改写后的检索查询，未改写时为 null
 * @param sources        引用的短剧来源，按相关度排序并截断到 topK
 * @param degraded       本次推荐是否发生降级
 * @param degradedFlags  降级原因标记，如 generation、timeout
 * @param elapsedMs      推荐耗时（毫秒）
 */
public record RagRecommendResponse(
        String answer,
        String rewrittenQuery,
        List<RagSource> sources,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs
) {
}
