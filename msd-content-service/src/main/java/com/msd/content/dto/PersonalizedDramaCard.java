package com.msd.content.dto;

/**
 * 首页个性化推荐卡片，字段与前端 DramaCard 对齐。
 *
 * @param dramaId      短剧 ID
 * @param title        标题
 * @param subtitle     副标题
 * @param coverUrl     封面地址
 * @param categoryName 分类名
 * @param episodeCount 总集数
 * @param viewCount    播放量
 * @param likeCount    点赞量
 */
public record PersonalizedDramaCard(
        Long dramaId,
        String title,
        String subtitle,
        String coverUrl,
        String categoryName,
        Integer episodeCount,
        Long viewCount,
        Long likeCount
) {
}
