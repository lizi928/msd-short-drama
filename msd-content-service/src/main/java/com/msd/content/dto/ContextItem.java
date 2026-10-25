package com.msd.content.dto;

import java.util.List;

/**
 * 一条前情检索结果。
 *
 * @param episodeId        剧集 ID
 * @param episodeNo        集数序号
 * @param episodeTitle     剧集标题
 * @param summary          分集摘要，已按配置截断
 * @param score            RRF 融合分数
 * @param retrievalSources 命中的检索来源，如 vector、bm25
 */
public record ContextItem(
        Long episodeId,
        Integer episodeNo,
        String episodeTitle,
        String summary,
        double score,
        List<String> retrievalSources
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public ContextItem {
        episodeTitle = episodeTitle == null ? "" : episodeTitle;
        summary = summary == null ? "" : summary;
        retrievalSources = retrievalSources == null ? List.of() : List.copyOf(retrievalSources);
    }
}
