package com.msd.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.interaction.entity.OutboxEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事务性发件箱 Mapper。
 */
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 锁定一批到期的待发送事件。
     * FOR UPDATE SKIP LOCKED 让多实例 relay 各自领取不同行，避免重复发送。
     */
    @Select("SELECT * FROM t_outbox_event "
            + "WHERE status = 0 AND next_retry_time <= NOW(3) "
            + "ORDER BY id LIMIT #{batchSize} FOR UPDATE SKIP LOCKED")
    List<OutboxEvent> selectPending(@Param("batchSize") int batchSize);

    /**
     * 按事件 ID 查询 outbox 状态。
     */
    @Select("SELECT * FROM t_outbox_event WHERE event_id = #{eventId}")
    OutboxEvent selectByEventId(@Param("eventId") String eventId);

    /**
     * 查询最近的 outbox 事件，供测试台展示投递状态。
     */
    @Select("SELECT * FROM t_outbox_event ORDER BY id DESC LIMIT #{limit}")
    List<OutboxEvent> selectRecent(@Param("limit") int limit);

    /**
     * 标记发送成功，status 0 -> 1。
     */
    @Update("UPDATE t_outbox_event SET status = 1, sent_at = NOW(3), last_error = '' "
            + "WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") Long id);

    /**
     * 记录本次失败并安排下次重试，状态保持 0。
     */
    @Update("UPDATE t_outbox_event SET retry_count = #{retryCount}, "
            + "next_retry_time = #{nextRetryTime}, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 0")
    int markRetry(@Param("id") Long id,
                  @Param("retryCount") int retryCount,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime,
                  @Param("lastError") String lastError);

    /**
     * 超过最大重试次数，status 0 -> 2 进入死信，等待人工处理。
     */
    @Update("UPDATE t_outbox_event SET status = 2, retry_count = #{retryCount}, "
            + "last_error = #{lastError} WHERE id = #{id} AND status = 0")
    int markDead(@Param("id") Long id,
                 @Param("retryCount") int retryCount,
                 @Param("lastError") String lastError);
}
