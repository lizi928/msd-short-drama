package com.msd.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.common.result.PageResult;
import com.msd.drama.dto.DramaEpisodeTitleRow;
import com.msd.drama.dto.DramaTagNameRow;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaCategory;
import com.msd.drama.mapper.DramaCategoryMapper;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.DramaTagMapper;
import com.msd.drama.mapper.EpisodeMapper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 索引目录导出。
 *
 * <p>批量查询标签、剧集标题和分类，避免按短剧逐条查询造成 N+1。</p>
 */
@Service
public class DramaRagExportService {

    /** 导出单页最大条数 */
    private static final int MAX_PAGE_SIZE = 500;

    // 短剧 Mapper
    private final DramaMapper dramaMapper;
    // 分类 Mapper
    private final DramaCategoryMapper categoryMapper;
    // 标签 Mapper
    private final DramaTagMapper tagMapper;
    // 剧集 Mapper
    private final EpisodeMapper episodeMapper;

    /**
     * 注入短剧、分类、标签和剧集 Mapper。
     */
    public DramaRagExportService(DramaMapper dramaMapper,
                                 DramaCategoryMapper categoryMapper,
                                 DramaTagMapper tagMapper,
                                 EpisodeMapper episodeMapper) {
        this.dramaMapper = dramaMapper;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.episodeMapper = episodeMapper;
    }

    /**
     * 分页导出已上架短剧及其标签、演员和在线剧集标题。
     */
    public PageResult<DramaRagExportDTO> export(int page, int size) {
        // 1. 规整分页参数，避免负数或过大的 size 打到数据库
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // 2. 先查总数，空目录直接返回
        long total = dramaMapper.countRagExport();
        if (total == 0) {
            return PageResult.empty(safePage, safeSize);
        }

        // 3. 按 ID 升序分页查询短剧
        int offset = (safePage - 1) * safeSize;
        List<Drama> dramas = dramaMapper.selectRagExport(offset, safeSize);
        if (dramas.isEmpty()) {
            return PageResult.of(safePage, safeSize, total, List.of());
        }

        // 4. 一次查询分类名，按分类 ID 建映射
        Map<Long, String> categoryNames = categoryMapper.selectList(new LambdaQueryWrapper<DramaCategory>())
                .stream()
                .collect(Collectors.toMap(DramaCategory::getId, DramaCategory::getName));

        // 5. 批量查询标签和在线剧集明细，按短剧 ID 分组
        List<Long> dramaIds = dramas.stream().map(Drama::getId).toList();
        Map<Long, List<String>> tagNames = loadTagNames(dramaIds);
        Map<Long, List<EpisodeRagExportDTO>> episodes = loadEpisodes(dramaIds);

        // 6. 由分集明细生成兼容字段“第N集 标题”，供推荐展示使用
        Map<Long, List<String>> episodeTitles = formatEpisodeTitles(episodes);

        // 7. 组装导出 DTO，保持数据库分页顺序
        List<DramaRagExportDTO> records = dramas.stream()
                .map(drama -> new DramaRagExportDTO(
                        drama.getId(),
                        drama.getTitle(),
                        drama.getSubtitle(),
                        drama.getCoverUrl(),
                        drama.getDescription(),
                        drama.getCategoryId(),
                        categoryNames.get(drama.getCategoryId()),
                        tagNames.getOrDefault(drama.getId(), List.of()),
                        splitActors(drama.getActors()),
                        drama.getEpisodeCount(),
                        episodeTitles.getOrDefault(drama.getId(), List.of()),
                        drama.getPublishTime() == null
                                ? null
                                : drama.getPublishTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        drama.getViewCount(),
                        drama.getLikeCount(),
                        drama.getFavoriteCount(),
                        drama.getCommentCount(),
                        episodes.getOrDefault(drama.getId(), List.of())))
                .toList();

        return PageResult.of(safePage, safeSize, total, records);
    }

    /**
     * 批量查询可见标签并按短剧 ID 分组。
     */
    private Map<Long, List<String>> loadTagNames(List<Long> dramaIds) {
        // 1. 一次查询关系表，避免每部短剧单独查标签
        List<DramaTagNameRow> rows = tagMapper.selectVisibleTagNamesByDramaIds(dramaIds);
        // 2. 分组时保留标签 ID 升序
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        for (DramaTagNameRow row : rows) {
            grouped.computeIfAbsent(row.getDramaId(), key -> new ArrayList<>()).add(row.getName());
        }
        return grouped;
    }

    /**
     * 批量查询在线剧集标题和摘要，并按短剧 ID 分组。
     */
    private Map<Long, List<EpisodeRagExportDTO>> loadEpisodes(List<Long> dramaIds) {
        // 1. 一次查询剧集表，同时拿到 ID、标题和摘要，避免每部短剧单独查剧集
        List<DramaEpisodeTitleRow> rows = episodeMapper.selectOnlineEpisodeTitlesByDramaIds(dramaIds);
        // 2. 按短剧分组，保持集数升序
        Map<Long, List<EpisodeRagExportDTO>> grouped = new LinkedHashMap<>();
        for (DramaEpisodeTitleRow row : rows) {
            grouped.computeIfAbsent(row.getDramaId(), key -> new ArrayList<>())
                    .add(new EpisodeRagExportDTO(
                            row.getEpisodeId(),
                            row.getEpisodeNo(),
                            row.getTitle() == null ? "" : row.getTitle().trim(),
                            row.getSummary() == null ? "" : row.getSummary()));
        }
        return grouped;
    }

    /**
     * 把分集明细格式化为“第N集 标题”，保持集数升序。
     *
     * <p>标题已带“第N集”前缀时不再重复拼接，避免污染 embedding 文本。</p>
     */
    private Map<Long, List<String>> formatEpisodeTitles(Map<Long, List<EpisodeRagExportDTO>> episodes) {
        // 1. 逐部短剧拼接标题，空标题只保留集数
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        episodes.forEach((dramaId, items) -> {
            List<String> titles = new ArrayList<>(items.size());
            for (EpisodeRagExportDTO item : items) {
                String prefix = "第" + item.episodeNo() + "集";
                String title = item.title() == null ? "" : item.title().trim();
                if (title.isEmpty()) {
                    titles.add(prefix);
                } else if (title.startsWith(prefix)) {
                    titles.add(title);
                } else {
                    titles.add(prefix + " " + title);
                }
            }
            grouped.put(dramaId, titles);
        });
        return grouped;
    }

    /**
     * 拆分逗号分隔的演员列表，兼容中英文逗号和顿号。
     */
    private List<String> splitActors(String actors) {
        // 1. 空值直接返回空列表
        if (actors == null || actors.isBlank()) {
            return List.of();
        }
        // 2. 去空白并过滤空项
        return Arrays.stream(actors.split("[,，、]"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }
}
