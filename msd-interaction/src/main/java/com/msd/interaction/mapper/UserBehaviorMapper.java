package com.msd.interaction.mapper;

import com.msd.interaction.dto.UserBehaviorRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户行为聚合 Mapper，只读查询 interaction 自己的表。
 */
public interface UserBehaviorMapper {

    /**
     * 按短剧聚合收藏、点赞和观看权重，同一部剧取最大权重。
     *
     * @param userId 用户 ID
     * @param since  时间窗口起点
     * @return 按权重、最近时间倒序的行为行
     */
    @Select("""
            SELECT drama_id AS dramaId,
                   MAX(weight) AS weight,
                   MAX(lastTime) AS lastTime
            FROM (
                SELECT drama_id, 3.0 AS weight, create_time AS lastTime
                FROM t_user_favorite
                WHERE user_id = #{userId} AND create_time >= #{since}
                UNION ALL
                SELECT target_id AS drama_id, 2.0 AS weight, create_time AS lastTime
                FROM t_user_like
                WHERE user_id = #{userId} AND target_type = 1 AND create_time >= #{since}
                UNION ALL
                SELECT drama_id,
                       CASE WHEN finished = 1 THEN 1.5 ELSE 1.0 END AS weight,
                       last_watch_time AS lastTime
                FROM t_watch_history
                WHERE user_id = #{userId} AND last_watch_time >= #{since}
            ) signals
            GROUP BY drama_id
            ORDER BY weight DESC, lastTime DESC
            """)
    List<UserBehaviorRow> selectWeights(@Param("userId") Long userId,
                                        @Param("since") LocalDateTime since);

    /**
     * 查询用户最近收藏的短剧 ID。
     */
    @Select("SELECT drama_id FROM t_user_favorite "
            + "WHERE user_id = #{userId} ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<Long> selectFavoriteDramaIds(@Param("userId") Long userId,
                                      @Param("limit") int limit);
}
