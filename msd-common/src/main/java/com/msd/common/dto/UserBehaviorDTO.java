package com.msd.common.dto;

import java.util.List;

/**
 * 用户短剧行为聚合结果，供推荐服务合成画像。
 *
 * <p>只包含行为权重和短剧 ID，不包含分类、标签等 drama 元数据。</p>
 *
 * @param userId           用户 ID
 * @param weights          按短剧聚合的权重，已去重
 * @param recentDramaIds   最近互动的短剧 ID，按时间倒序
 * @param favoriteDramaIds 收藏的短剧 ID，按收藏时间倒序
 */
public record UserBehaviorDTO(
        Long userId,
        List<DramaWeight> weights,
        List<Long> recentDramaIds,
        List<Long> favoriteDramaIds
) {

    /**
     * 单部短剧的行为权重。
     *
     * @param dramaId 短剧 ID
     * @param weight  收藏/点赞/观看聚合后的最大权重
     */
    public record DramaWeight(Long dramaId, double weight) {
    }

    /**
     * 空行为，避免调用方处理 null。
     */
    public static UserBehaviorDTO empty(Long userId) {
        return new UserBehaviorDTO(userId, List.of(), List.of(), List.of());
    }
}
