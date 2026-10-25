package com.msd.content.audit;

import java.util.List;

/**
 * 单部短剧的 AI 审核结果。
 *
 * @param dramaId       短剧 ID
 * @param aiReviewStatus AI 审核状态：2 通过、3 驳回、4 失败
 * @param updated       是否成功写回 drama
 * @param degradedFlags 降级标记
 */
public record DramaReviewAuditItem(
        Long dramaId,
        Integer aiReviewStatus,
        boolean updated,
        List<String> degradedFlags
) {

    /**
     * 统一空值。
     */
    public DramaReviewAuditItem {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
