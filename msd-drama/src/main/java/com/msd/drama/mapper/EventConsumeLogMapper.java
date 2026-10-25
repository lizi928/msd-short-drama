package com.msd.drama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.drama.entity.EventConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * Kafka 消费幂等日志 Mapper。
 */
public interface EventConsumeLogMapper extends BaseMapper<EventConsumeLog> {

    /**
     * 幂等记录消费事件，返回 1 表示首次消费，0 表示重复。
     * (consumer_group, event_id) 上有唯一键，靠 INSERT IGNORE 拦截重复事件。
     */
    @Insert("INSERT IGNORE INTO t_event_consume_log (consumer_group, event_id, topic) "
            + "VALUES (#{consumerGroup}, #{eventId}, #{topic})")
    int insertIgnore(@Param("consumerGroup") String consumerGroup,
                     @Param("eventId") String eventId,
                     @Param("topic") String topic);
}
