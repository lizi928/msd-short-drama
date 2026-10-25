package com.msd.interaction.dto;

import java.time.LocalDateTime;

/**
 * 管理员评论列表项。
 *
 * @param commentId  评论 ID
 * @param dramaId    短剧 ID
 * @param episodeId  剧集 ID，空表示短剧级评论
 * @param userId     评论作者
 * @param rootId     根评论 ID
 * @param parentId   父评论 ID
 * @param content    评论内容
 * @param likeCount  点赞数
 * @param replyCount 回复数
 * @param status     状态：0 待审、1 可见、2 隐藏、3 已删除
 * @param createTime 创建时间
 */
public record AdminCommentItem(
        Long commentId,
        Long dramaId,
        Long episodeId,
        Long userId,
        Long rootId,
        Long parentId,
        String content,
        Long likeCount,
        Long replyCount,
        Integer status,
        LocalDateTime createTime
) {
}
