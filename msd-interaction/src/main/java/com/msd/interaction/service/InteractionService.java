package com.msd.interaction.service;

import com.msd.common.event.InteractionEvent;
import com.msd.common.event.InteractionEventType;
import com.msd.common.event.KafkaTopics;
import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.interaction.dto.CommentRequest;
import com.msd.interaction.dto.CommentResult;
import com.msd.interaction.dto.FavoriteResult;
import com.msd.interaction.dto.HistoryRequest;
import com.msd.interaction.dto.HistoryResult;
import com.msd.interaction.dto.LikeResult;
import com.msd.interaction.entity.Comment;
import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.entity.WatchHistory;
import com.msd.interaction.mapper.CommentMapper;
import com.msd.interaction.mapper.OutboxEventMapper;
import com.msd.interaction.mapper.UserFavoriteMapper;
import com.msd.interaction.mapper.UserLikeMapper;
import com.msd.interaction.mapper.WatchHistoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 互动写模型：明细和 outbox 在同一个本地事务内落库。
 */
@Service
public class InteractionService {

    /** 点赞目标类型：短剧。 */
    private static final int TARGET_DRAMA = 1;
    /** 点赞目标类型：剧集。 */
    private static final int TARGET_EPISODE = 2;
    /** 点赞目标类型：评论。 */
    private static final int TARGET_COMMENT = 3;

    // 点赞 Mapper
    private final UserLikeMapper userLikeMapper;
    // 收藏 Mapper
    private final UserFavoriteMapper userFavoriteMapper;
    // 评论 Mapper
    private final CommentMapper commentMapper;
    // 观看历史 Mapper
    private final WatchHistoryMapper watchHistoryMapper;
    // outbox 事件 Mapper
    private final OutboxEventMapper outboxEventMapper;
    // JSON 序列化器，用于生成事件 payload
    private final JsonMapper jsonMapper;

    /**
     * 注入互动 Mapper 和 JSON 序列化器。
     */
    public InteractionService(UserLikeMapper userLikeMapper,
                              UserFavoriteMapper userFavoriteMapper,
                              CommentMapper commentMapper,
                              WatchHistoryMapper watchHistoryMapper,
                              OutboxEventMapper outboxEventMapper,
                              JsonMapper jsonMapper) {
        this.userLikeMapper = userLikeMapper;
        this.userFavoriteMapper = userFavoriteMapper;
        this.commentMapper = commentMapper;
        this.watchHistoryMapper = watchHistoryMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 点赞；明细和 LIKE 事件同一事务。
     */
    @Transactional
    public LikeResult like(Long userId, Integer targetType, Long targetId) {
        // 1. 校验目标类型
        validateTargetType(targetType);

        // 2. 幂等插入点赞，已存在时不重复写事件
        int inserted = userLikeMapper.insertIgnore(userId, targetType, targetId);
        if (inserted == 0) {
            return new LikeResult(true, null);
        }

        // 3. 评论点赞同步更新评论表计数，targetType 1/2 交给 drama 异步消费
        if (targetType == TARGET_COMMENT
                && commentMapper.incrementLikeCount(targetId) != 1) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }

        // 4. 写 LIKE 事件
        OutboxEvent event = buildEvent(InteractionEventType.LIKE, userId, targetType, targetId,
                targetType == TARGET_DRAMA ? targetId : null,
                targetType == TARGET_EPISODE ? targetId : null,
                targetType == TARGET_COMMENT ? targetId : null,
                null, null, null);
        outboxEventMapper.insert(event);
        return new LikeResult(true, event.getEventId());
    }

    /**
     * 取消点赞；只有实际删除成功才写 UNLIKE 事件。
     */
    @Transactional
    public LikeResult unlike(Long userId, Integer targetType, Long targetId) {
        // 1. 校验目标类型
        validateTargetType(targetType);

        // 2. 删除点赞，没删到行说明本来未点赞
        if (userLikeMapper.deleteByUserAndTarget(userId, targetType, targetId) == 0) {
            return new LikeResult(false, null);
        }

        // 3. 评论点赞同步扣减本地计数
        if (targetType == TARGET_COMMENT) {
            commentMapper.decrementLikeCount(targetId);
        }

        // 4. 写 UNLIKE 事件
        OutboxEvent event = buildEvent(InteractionEventType.UNLIKE, userId, targetType, targetId,
                targetType == TARGET_DRAMA ? targetId : null,
                targetType == TARGET_EPISODE ? targetId : null,
                targetType == TARGET_COMMENT ? targetId : null,
                null, null, null);
        outboxEventMapper.insert(event);
        return new LikeResult(false, event.getEventId());
    }

    /**
     * 收藏；明细和 FAVORITE 事件同一事务。
     */
    @Transactional
    public FavoriteResult favorite(Long userId, Long dramaId) {
        // 1. 幂等插入收藏
        int inserted = userFavoriteMapper.insertIgnore(userId, dramaId);
        if (inserted == 0) {
            return new FavoriteResult(true, null);
        }

        // 2. 写 FAVORITE 事件
        OutboxEvent event = buildEvent(InteractionEventType.FAVORITE, userId, TARGET_DRAMA, dramaId,
                dramaId, null, null, null, null, null);
        outboxEventMapper.insert(event);
        return new FavoriteResult(true, event.getEventId());
    }

    /**
     * 取消收藏；只有实际删除成功才写 UNFAVORITE 事件。
     */
    @Transactional
    public FavoriteResult unfavorite(Long userId, Long dramaId) {
        // 1. 删除收藏
        if (userFavoriteMapper.deleteByUserAndDrama(userId, dramaId) == 0) {
            return new FavoriteResult(false, null);
        }

        // 2. 写 UNFAVORITE 事件
        OutboxEvent event = buildEvent(InteractionEventType.UNFAVORITE, userId, TARGET_DRAMA, dramaId,
                dramaId, null, null, null, null, null);
        outboxEventMapper.insert(event);
        return new FavoriteResult(false, event.getEventId());
    }

    /**
     * 创建评论或回复；根评论 reply_count 只加在根评论上。
     */
    @Transactional
    public CommentResult createComment(Long userId, CommentRequest request) {
        // 1. 内容去空白后不能为空
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "评论内容不能为空");
        }

        // 2. 归一化根评论和父评论 ID
        long rootId = request.rootId() == null ? 0L : request.rootId();
        long parentId = request.parentId() == null ? 0L : request.parentId();
        if (rootId == 0L && parentId != 0L) {
            rootId = parentId;
        }

        // 3. 回复必须挂在同短剧/同剧集的可见根评论下
        Long replyToUserId = null;
        if (rootId != 0L) {
            Comment root = commentMapper.selectById(rootId);
            if (root == null
                    || !Integer.valueOf(1).equals(root.getStatus())
                    || root.getRootId() == null
                    || root.getRootId() != 0L) {
                throw new BizException(ResultCode.NOT_FOUND, "根评论不存在");
            }
            if (!request.dramaId().equals(root.getDramaId())
                    || !Objects.equals(request.episodeId(), root.getEpisodeId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "根评论与短剧/剧集不匹配");
            }

            if (parentId == 0L || parentId == rootId) {
                // 3.1 直接回复根评论
                parentId = rootId;
                replyToUserId = root.getUserId();
            } else {
                // 3.2 回复某个回复，父评论必须属于同一个根评论
                Comment parent = commentMapper.selectById(parentId);
                if (parent == null
                        || !Integer.valueOf(1).equals(parent.getStatus())
                        || parent.getRootId() == null
                        || parent.getRootId() != rootId) {
                    throw new BizException(ResultCode.PARAM_ERROR, "父评论不存在或不属于该根评论");
                }
                if (!request.dramaId().equals(parent.getDramaId())
                        || !Objects.equals(request.episodeId(), parent.getEpisodeId())) {
                    throw new BizException(ResultCode.PARAM_ERROR, "父评论与短剧/剧集不匹配");
                }
                replyToUserId = parent.getUserId();
            }
        }

        // 4. 写评论明细
        Comment comment = new Comment();
        comment.setDramaId(request.dramaId());
        comment.setEpisodeId(request.episodeId());
        comment.setUserId(userId);
        comment.setRootId(rootId);
        comment.setParentId(parentId);
        comment.setReplyToUserId(replyToUserId);
        comment.setContent(content);
        comment.setLikeCount(0L);
        comment.setReplyCount(0L);
        comment.setStatus(1);
        commentMapper.insert(comment);

        // 5. 回复时增加根评论的回复数
        if (rootId != 0L && commentMapper.incrementReplyCount(rootId) != 1) {
            throw new BizException(ResultCode.NOT_FOUND, "根评论不存在");
        }

        // 6. 写 COMMENT 事件
        OutboxEvent event = buildEvent(InteractionEventType.COMMENT, userId, null, null,
                request.dramaId(), request.episodeId(), comment.getId(), rootId, parentId, null);
        outboxEventMapper.insert(event);
        return new CommentResult(comment.getId(), event.getEventId());
    }

    /**
     * 逻辑删除自己的可见评论；只有状态真正变化才写事件。
     */
    @Transactional
    public boolean deleteComment(Long userId, Long commentId) {
        // 1. 查询评论并校验存在、作者和可见状态
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }
        if (!userId.equals(comment.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权删除该评论");
        }
        if (!Integer.valueOf(1).equals(comment.getStatus())) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }

        // 2. 根评论：级联删除可见回复，并逐条写删除事件
        if (comment.getRootId() != null && comment.getRootId() == 0L) {
            List<Comment> replies = commentMapper.selectVisibleReplies(commentId);
            if (commentMapper.softDelete(commentId, userId) == 0) {
                throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
            }
            insertCommentDeleted(userId, comment);

            for (Comment reply : replies) {
                if (commentMapper.softDeleteById(reply.getId()) == 1) {
                    insertCommentDeleted(userId, reply);
                }
            }
            return true;
        }

        // 3. 回复：软删除并减少根评论回复数
        if (commentMapper.softDelete(commentId, userId) == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }
        if (comment.getRootId() != null && comment.getRootId() != 0L) {
            commentMapper.decrementReplyCount(comment.getRootId());
        }
        insertCommentDeleted(userId, comment);
        return true;
    }

    /**
     * 管理员隐藏评论；根评论级联隐藏可见回复。
     */
    @Transactional
    public boolean adminHideComment(Long commentId) {
        // 1. 校验评论存在且未删除
        Comment comment = requireCommentForModeration(commentId);
        if (Integer.valueOf(2).equals(comment.getStatus())) {
            return true;
        }
        boolean wasVisible = Integer.valueOf(1).equals(comment.getStatus());

        // 2. 根评论：级联隐藏未删除回复，只对原本可见的评论发删除事件
        if (isRoot(comment)) {
            List<Comment> replies = commentMapper.selectActiveReplies(commentId);
            if (commentMapper.adminHide(commentId) == 0) {
                throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
            }
            if (wasVisible) {
                insertCommentDeleted(comment.getUserId(), comment);
            }
            for (Comment reply : replies) {
                boolean replyVisible = Integer.valueOf(1).equals(reply.getStatus());
                if (commentMapper.adminHide(reply.getId()) == 1 && replyVisible) {
                    insertCommentDeleted(reply.getUserId(), reply);
                }
            }
            return true;
        }

        // 3. 回复：隐藏后减少根评论回复数
        if (commentMapper.adminHide(commentId) == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }
        if (wasVisible) {
            if (comment.getRootId() != null && comment.getRootId() != 0L) {
                commentMapper.decrementReplyCount(comment.getRootId());
            }
            insertCommentDeleted(comment.getUserId(), comment);
        }
        return true;
    }

    /**
     * 管理员删除评论；根评论级联删除未删除回复。
     */
    @Transactional
    public boolean adminDeleteComment(Long commentId) {
        // 1. 校验评论存在且未删除
        Comment comment = requireCommentForModeration(commentId);
        boolean wasVisible = Integer.valueOf(1).equals(comment.getStatus());

        // 2. 根评论：级联删除回复，只对原本可见的评论发删除事件
        if (isRoot(comment)) {
            List<Comment> replies = commentMapper.selectActiveReplies(commentId);
            if (commentMapper.adminDeleteById(commentId) == 0) {
                throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
            }
            if (wasVisible) {
                insertCommentDeleted(comment.getUserId(), comment);
            }
            for (Comment reply : replies) {
                boolean replyVisible = Integer.valueOf(1).equals(reply.getStatus());
                if (commentMapper.adminDeleteById(reply.getId()) == 1 && replyVisible) {
                    insertCommentDeleted(reply.getUserId(), reply);
                }
            }
            return true;
        }

        // 3. 回复：删除后减少根评论回复数
        if (commentMapper.adminDeleteById(commentId) == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }
        if (wasVisible) {
            if (comment.getRootId() != null && comment.getRootId() != 0L) {
                commentMapper.decrementReplyCount(comment.getRootId());
            }
            insertCommentDeleted(comment.getUserId(), comment);
        }
        return true;
    }

    /**
     * 查询未删除评论，不存在或已删除时返回 404。
     */
    private Comment requireCommentForModeration(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || Integer.valueOf(3).equals(comment.getStatus())) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }
        return comment;
    }

    /**
     * 判断是否根评论。
     */
    private boolean isRoot(Comment comment) {
        return Long.valueOf(0L).equals(comment.getRootId());
    }

    /**
     * 写一条评论删除事件。
     */
    private void insertCommentDeleted(Long userId, Comment comment) {
        OutboxEvent event = buildEvent(InteractionEventType.COMMENT_DELETED, userId, null, null,
                comment.getDramaId(), comment.getEpisodeId(), comment.getId(),
                comment.getRootId(), comment.getParentId(), null);
        outboxEventMapper.insert(event);
    }

    /**
     * 上报观看进度；只有首次插入才写 HISTORY 事件。
     */
    @Transactional
    public HistoryResult recordHistory(Long userId, HistoryRequest request) {
        // 1. 组装观看历史
        WatchHistory history = new WatchHistory();
        history.setUserId(userId);
        history.setDramaId(request.dramaId());
        history.setEpisodeId(request.episodeId());
        history.setPositionMs(request.positionMs());
        history.setDurationMs(request.durationMs());
        history.setFinished(Boolean.TRUE.equals(request.finished()) ? 1 : 0);

        // 2. upsert；返回 1 表示首次插入
        boolean firstWatch = watchHistoryMapper.upsert(history) == 1;
        if (!firstWatch) {
            return new HistoryResult(false, null);
        }

        // 3. 首次观看写 HISTORY 事件
        OutboxEvent event = buildEvent(InteractionEventType.HISTORY, userId, null, null,
                request.dramaId(), request.episodeId(), null, null, null, true);
        outboxEventMapper.insert(event);
        return new HistoryResult(true, event.getEventId());
    }

    /**
     * 删除用户某集的历史记录。
     */
    public boolean deleteHistory(Long userId, Long episodeId) {
        // 1. 物理删除，返回是否真的删除了记录
        return watchHistoryMapper.deleteByUserAndEpisode(userId, episodeId) > 0;
    }

    /**
     * 清空用户全部观看历史。
     */
    public int clearHistory(Long userId) {
        // 1. 返回删除条数
        return watchHistoryMapper.deleteByUser(userId);
    }

    /**
     * 校验点赞目标类型。
     */
    private void validateTargetType(Integer targetType) {
        if (targetType == null || targetType < TARGET_DRAMA || targetType > TARGET_COMMENT) {
            throw new BizException(ResultCode.PARAM_ERROR, "targetType 只能是 1/2/3");
        }
    }

    /**
     * 构造事件信封并写入 outbox。
     */
    private OutboxEvent buildEvent(InteractionEventType eventType,
                                   Long userId,
                                   Integer targetType,
                                   Long targetId,
                                   Long dramaId,
                                   Long episodeId,
                                   Long commentId,
                                   Long rootId,
                                   Long parentId,
                                   Boolean firstWatch) {
        // 1. 组装事件体
        String eventId = UUID.randomUUID().toString();
        InteractionEvent eventBody = new InteractionEvent(
                eventId, eventType, userId, targetType, targetId, dramaId, episodeId,
                commentId, rootId, parentId, firstWatch, System.currentTimeMillis());

        // 2. 按事件类型确定 topic、聚合根和分区键
        String topic;
        String aggregateType;
        Long aggregateId;
        String eventKey;
        switch (eventType) {
            case LIKE, UNLIKE -> {
                topic = KafkaTopics.INTERACTION_LIKE;
                aggregateType = switch (targetType) {
                    case TARGET_DRAMA -> "DRAMA";
                    case TARGET_EPISODE -> "EPISODE";
                    default -> "COMMENT";
                };
                aggregateId = targetId;
                eventKey = switch (targetType) {
                    case TARGET_DRAMA -> "drama:" + targetId;
                    case TARGET_EPISODE -> "episode:" + targetId;
                    default -> "comment:" + targetId;
                };
            }
            case FAVORITE, UNFAVORITE -> {
                topic = KafkaTopics.INTERACTION_FAVORITE;
                aggregateType = "DRAMA";
                aggregateId = dramaId;
                eventKey = "drama:" + dramaId;
            }
            case COMMENT, COMMENT_DELETED -> {
                topic = KafkaTopics.INTERACTION_COMMENT;
                aggregateType = "COMMENT";
                aggregateId = commentId;
                eventKey = "drama:" + dramaId;
            }
            case HISTORY -> {
                topic = KafkaTopics.INTERACTION_HISTORY;
                aggregateType = "EPISODE";
                aggregateId = episodeId;
                eventKey = "episode:" + episodeId;
            }
            default -> throw new BizException(ResultCode.SYSTEM_ERROR, "未知事件类型");
        }

        // 3. 序列化 payload；失败时让本地事务回滚
        String payload;
        try {
            payload = jsonMapper.writeValueAsString(eventBody);
        } catch (Exception ex) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "事件序列化失败");
        }

        // 4. 组装 outbox 行，status=0 待发送
        OutboxEvent outbox = new OutboxEvent();
        outbox.setEventId(eventId);
        outbox.setTopic(topic);
        outbox.setEventType(eventType.name());
        outbox.setAggregateType(aggregateType);
        outbox.setAggregateId(aggregateId);
        outbox.setEventKey(eventKey);
        outbox.setPayload(payload);
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        return outbox;
    }
}
