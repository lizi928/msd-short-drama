package com.msd.common.dto;

/**
 * AI 伴看使用的剧集元信息。
 *
 * <p>只有在线剧集且所属短剧已上架时才返回；summary 为空表示还没有可用摘要。</p>
 *
 * @param episodeId        剧集 ID
 * @param dramaId          所属短剧 ID
 * @param dramaTitle       短剧标题
 * @param episodeNo        集数序号，从 1 开始
 * @param episodeTitle     剧集标题
 * @param summary          分集摘要，没有摘要时为空字符串
 * @param summaryStatus    摘要状态：0 缺失，1 生成中，2 可用，3 失败
 * @param dramaDescription 短剧简介，已截断，仅用于生成摘要提示
 */
public record CompanionEpisodeDTO(
        Long episodeId,
        Long dramaId,
        String dramaTitle,
        Integer episodeNo,
        String episodeTitle,
        String summary,
        Integer summaryStatus,
        String dramaDescription
) {
}
