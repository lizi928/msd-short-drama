package com.msd.drama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.drama.dto.CompanionEpisodeRow;
import com.msd.drama.dto.DramaEpisodeTitleRow;
import com.msd.drama.dto.EpisodeSourceRow;
import com.msd.drama.entity.DramaEpisode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 剧集 Mapper。
 */
public interface EpisodeMapper extends BaseMapper<DramaEpisode> {

    /**
     * 解锁计数加一，返回影响行数。
     */
    @Update("UPDATE t_drama_episode SET unlock_count = unlock_count + 1 WHERE id = #{episodeId}")
    int incrementUnlockCount(@Param("episodeId") Long episodeId);

    /**
     * 查询剧集所属短剧 ID，供缓存失效定位 detail key。
     */
    @Select("SELECT drama_id FROM t_drama_episode WHERE id = #{episodeId}")
    Long selectDramaIdById(@Param("episodeId") Long episodeId);

    /**
     * 按短剧查询剧集列表，按集数升序。
     */
    @Select("SELECT id, drama_id, episode_no, title, price_points, status, "
            + "play_count, unlock_count, like_count, comment_count "
            + "FROM t_drama_episode WHERE drama_id = #{dramaId} ORDER BY episode_no")
    List<DramaEpisode> selectByDramaId(@Param("dramaId") Long dramaId);

    /**
     * 查询短剧下的在线剧集，按集数升序；不查询视频地址。
     */
    @Select("""
            SELECT id, drama_id, episode_no, title, cover_url, duration_seconds,
                   price_points, status, play_count, unlock_count, like_count, comment_count
            FROM t_drama_episode
            WHERE drama_id = #{dramaId} AND status = 1
            ORDER BY episode_no
            """)
    List<DramaEpisode> selectOnlineByDramaId(@Param("dramaId") Long dramaId);

    /**
     * 查询可播放的剧集源，同时校验剧集在线和短剧已上架。
     */
    @Select("""
            SELECT e.id AS episode_id, e.drama_id, d.title AS drama_title,
                   e.episode_no, e.title AS episode_title, e.video_url,
                   e.cover_url, e.duration_seconds, e.price_points
            FROM t_drama_episode e
            JOIN t_drama d ON d.id = e.drama_id
            WHERE e.id = #{episodeId}
              AND e.status = 1
              AND d.deleted = 0
              AND d.review_status = 2
              AND d.publish_time IS NOT NULL
            """)
    EpisodeSourceRow selectSourceById(@Param("episodeId") Long episodeId);

    /**
     * 按短剧 ID 批量查询在线剧集标题和摘要，避免 RAG 导出时的 N+1 查询。
     */
    @Select("""
            <script>
            SELECT id AS episode_id, drama_id, episode_no, title, summary
            FROM t_drama_episode
            WHERE status = 1
              AND drama_id IN
              <foreach collection="dramaIds" item="dramaId" open="(" separator="," close=")">#{dramaId}</foreach>
            ORDER BY drama_id, episode_no
            </script>
            """)
    List<DramaEpisodeTitleRow> selectOnlineEpisodeTitlesByDramaIds(@Param("dramaIds") List<Long> dramaIds);

    /**
     * 查询 AI 伴看所需的剧集元信息。
     *
     * <p>可见性条件与播放源一致：剧集在线、短剧未删除、已通过审核且已上架。</p>
     */
    @Select("""
            SELECT e.id AS episode_id, e.drama_id, d.title AS drama_title,
                   e.episode_no, e.title AS episode_title, e.summary, e.summary_status,
                   d.description AS drama_description
            FROM t_drama_episode e
            JOIN t_drama d ON d.id = e.drama_id
            WHERE e.id = #{episodeId}
              AND e.status = 1
              AND d.deleted = 0
              AND d.review_status = 2
              AND d.publish_time IS NOT NULL
            """)
    CompanionEpisodeRow selectCompanionEpisodeById(@Param("episodeId") Long episodeId);

    /**
     * 查询可生成摘要的在线剧集 ID。
     *
     * <p>只返回已上架短剧下、摘要为空且状态可重试或认领已过期的剧集。</p>
     */
    @Select("""
            SELECT e.id
            FROM t_drama_episode e
            JOIN t_drama d ON d.id = e.drama_id
            WHERE e.drama_id = #{dramaId}
              AND e.status = 1
              AND d.deleted = 0
              AND d.review_status = 2
              AND d.publish_time IS NOT NULL
              AND (e.summary IS NULL OR e.summary = '')
              AND (e.summary_status IN (0, 3)
                   OR (e.summary_status = 1
                       AND (e.summary_updated_at IS NULL
                            OR e.summary_updated_at < DATE_SUB(NOW(3), INTERVAL 60 SECOND))))
            ORDER BY e.episode_no
            LIMIT #{limit}
            """)
    List<Long> selectSummaryCandidates(@Param("dramaId") Long dramaId,
                                       @Param("limit") int limit);

    /**
     * 认领摘要生成权；只有空摘要且状态可重试或认领超时才成功。
     *
     * @return 影响行数，1 表示认领成功
     */
    @Update("""
            UPDATE t_drama_episode
            SET summary_status = 1, summary_updated_at = NOW(3)
            WHERE id = #{episodeId}
              AND (summary IS NULL OR summary = '')
              AND (summary_status IN (0, 3)
                   OR (summary_status = 1
                       AND (summary_updated_at IS NULL
                            OR summary_updated_at < DATE_SUB(NOW(3), INTERVAL 60 SECOND))))
            """)
    int claimSummary(@Param("episodeId") Long episodeId);

    /**
     * 回写生成的摘要；已有摘要时不会覆盖。
     *
     * @return 影响行数，1 表示写入成功
     */
    @Update("""
            UPDATE t_drama_episode
            SET summary = #{summary}, summary_status = 2, summary_updated_at = NOW(3)
            WHERE id = #{episodeId} AND (summary IS NULL OR summary = '')
            """)
    int writebackSummary(@Param("episodeId") Long episodeId,
                         @Param("summary") String summary);

    /**
     * 释放认领并标记失败，允许后续重试。
     *
     * @return 影响行数，1 表示释放成功
     */
    @Update("""
            UPDATE t_drama_episode
            SET summary_status = 3, summary_updated_at = NOW(3)
            WHERE id = #{episodeId} AND summary_status = 1
            """)
    int releaseSummaryClaim(@Param("episodeId") Long episodeId);

    /**
     * 创作者新增或更新剧集；video_url 使用空字符串，Demo 不存真实视频。
     */
    @Insert("""
            INSERT INTO t_drama_episode
                (drama_id, episode_no, title, video_url, cover_url,
                 duration_seconds, price_points, status)
            VALUES
                (#{dramaId}, #{episodeNo}, #{title}, '', '',
                 #{durationSeconds}, #{pricePoints}, #{status})
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                duration_seconds = VALUES(duration_seconds),
                price_points = VALUES(price_points),
                status = VALUES(status)
            """)
    int upsertEpisode(@Param("dramaId") Long dramaId,
                      @Param("episodeNo") Integer episodeNo,
                      @Param("title") String title,
                      @Param("durationSeconds") Integer durationSeconds,
                      @Param("pricePoints") Long pricePoints,
                      @Param("status") Integer status);

    /**
     * 把本次未提交的剧集下线，避免剧集被误删。
     */
    @Update("""
            <script>
            UPDATE t_drama_episode SET status = 0
            WHERE drama_id = #{dramaId} AND status = 1
            <if test="episodeNos != null and episodeNos.size() > 0">
              AND episode_no NOT IN
              <foreach collection="episodeNos" item="episodeNo" open="(" separator="," close=")">
                #{episodeNo}
              </foreach>
            </if>
            </script>
            """)
    int offlineMissingEpisodes(@Param("dramaId") Long dramaId,
                               @Param("episodeNos") List<Integer> episodeNos);
}
