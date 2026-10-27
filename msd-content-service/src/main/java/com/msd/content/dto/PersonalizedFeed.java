package com.msd.content.dto;

import java.util.List;

/**
 * 首页「为你推荐」结果。
 *
 * @param items        推荐卡片
 * @param personalized 是否命中用户偏好；false 表示回退热播
 * @param coldStart    是否冷启动
 * @param degraded     是否因偏好/目录依赖失败而降级
 */
public record PersonalizedFeed(
        List<PersonalizedDramaCard> items,
        boolean personalized,
        boolean coldStart,
        boolean degraded
) {

    /**
     * 反序列化时保证 items 不为 null。
     */
    public PersonalizedFeed {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 空结果。
     */
    public static PersonalizedFeed empty(boolean degraded) {
        return new PersonalizedFeed(List.of(), false, false, degraded);
    }
}
