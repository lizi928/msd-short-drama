package com.msd.content.audit;

import java.util.List;

/**
 * 写回 t_drama.ai_review_result 的完整审核结果。
 *
 * @param scene         审核场景
 * @param verdict       审核结论；降级时为 null
 * @param degraded      是否降级
 * @param degradedFlags 降级标记
 * @param elapsedMs     审核耗时
 * @param auditedAt     审核时间
 */
public record DramaAiReviewResult(
        String scene,
        AuditVerdict verdict,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs,
        String auditedAt
) {

    /**
     * 统一空值，避免写入 null 数组。
     */
    public DramaAiReviewResult {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
