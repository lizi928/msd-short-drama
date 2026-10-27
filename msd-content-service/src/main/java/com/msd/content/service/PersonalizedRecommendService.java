package com.msd.content.service;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.PersonalizedDramaCard;
import com.msd.content.dto.PersonalizedFeed;
import com.msd.content.dto.UserPreference;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首页「为你推荐」：基于用户偏好对已上架短剧做规则排序，不调用 LLM。
 *
 * <p>无偏好或依赖失败时回退热播，保证接口始终可返回。</p>
 */
@Service
public class PersonalizedRecommendService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(PersonalizedRecommendService.class);
    /** 缓存 key 前缀。 */
    private static final String CACHE_PREFIX = "msd:rag:for-you:";
    /** 默认返回条数。 */
    private static final int DEFAULT_LIMIT = 6;
    /** 最大返回条数。 */
    private static final int MAX_LIMIT = 12;
    /** 新上架判定天数。 */
    private static final int FRESH_DAYS = 30;
    /** Redis 连接失败后的退避时间（毫秒）。 */
    private static final long REDIS_BACKOFF_MS = 30_000L;

    // 用户偏好服务
    private final UserPreferenceService preferenceService;
    // 短剧目录缓存
    private final DramaCatalogCacheService catalogCache;
    // Redis 客户端
    private final RedissonClient redissonClient;
    // JSON 序列化
    private final JsonMapper jsonMapper;
    // RAG 配置
    private final RagProperties properties;
    // Redis 失败后的退避截止时间
    private volatile long redisUnavailableUntil;

    /**
     * 注入偏好、目录、缓存和配置。
     */
    public PersonalizedRecommendService(UserPreferenceService preferenceService,
                                        DramaCatalogCacheService catalogCache,
                                        @Lazy RedissonClient redissonClient,
                                        JsonMapper jsonMapper,
                                        RagProperties properties) {
        this.preferenceService = preferenceService;
        this.catalogCache = catalogCache;
        this.redissonClient = redissonClient;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    /**
     * 生成首页推荐；无用户时只回退热播。
     */
    public PersonalizedFeed feed(Long userId, int requestedLimit) {
        int limit = clampLimit(requestedLimit);
        // 1. 有用户时先读结果缓存
        String cacheKey = userId == null ? null : CACHE_PREFIX + userId + ":" + limit;
        PersonalizedFeed cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 读取目录和偏好；目录为空时直接返回空结果
        List<DramaRagExportDTO> catalog = catalogCache.list();
        if (catalog.isEmpty()) {
            return PersonalizedFeed.empty(true);
        }
        UserPreference preference = preferenceService.get(userId);
        PersonalizedFeed result;
        if (preference.empty()) {
            // 3. 冷启动/依赖失败回退热播
            result = new PersonalizedFeed(hotCards(catalog, limit), false, true, preference.degraded());
        } else {
            // 4. 按偏好打分，并排除最近已看/已收藏
            result = new PersonalizedFeed(rankByPreference(catalog, preference, limit), true, false, false);
        }

        // 5. 写入结果缓存；降级结果不缓存，方便快速恢复
        if (!result.degraded()) {
            writeCache(cacheKey, result);
        }
        return result;
    }

    /**
     * 按分类、标签、热度和上新时间排序。
     */
    private List<PersonalizedDramaCard> rankByPreference(List<DramaRagExportDTO> catalog,
                                                         UserPreference preference,
                                                         int limit) {
        Map<Long, Double> categoryScores = normalizeCategories(preference.categories());
        Map<String, Double> tagScores = normalizeTags(preference.tags());
        Set<Long> excluded = new HashSet<>();
        excluded.addAll(preference.recentDramaIds());
        excluded.addAll(preference.favoriteDramaIds());

        // 1. 计算每部剧的偏好分
        List<ScoredDrama> scored = new ArrayList<>();
        for (DramaRagExportDTO drama : catalog) {
            if (drama == null || drama.id() == null || excluded.contains(drama.id())) {
                continue;
            }
            double score = 3.0 * categoryScores.getOrDefault(drama.categoryId(), 0.0);
            score += 2.0 * tagScore(drama.tags(), tagScores);
            score += 0.5 * Math.log1p(popularity(drama));
            if (isFresh(drama.publishTime())) {
                score += 1.0;
            }
            scored.add(new ScoredDrama(drama, score));
        }

        // 2. 偏好分降序，热度兜底，保证结果稳定
        scored.sort(Comparator.comparingDouble(ScoredDrama::score).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (ScoredDrama item) -> popularity(item.drama())).reversed())
                .thenComparing(item -> item.drama().id(), Comparator.nullsLast(Long::compareTo)));

        List<DramaRagExportDTO> selected = scored.stream()
                .limit(limit)
                .map(ScoredDrama::drama)
                .toList();

        // 3. 结果不足时用热播补齐，避免首页区块过短
        if (selected.size() < limit) {
            Set<Long> selectedIds = new HashSet<>();
            selected.forEach(item -> selectedIds.add(item.id()));
            for (DramaRagExportDTO drama : hotDramas(catalog)) {
                if (selected.size() >= limit) {
                    break;
                }
                // 补齐时仍然排除最近已看/已收藏，避免推荐刚看过的剧
                if (drama.id() != null && !excluded.contains(drama.id())
                        && selectedIds.add(drama.id())) {
                    selected = new ArrayList<>(selected);
                    selected.add(drama);
                }
            }
        }
        return toCards(selected);
    }

    /**
     * 直接取热播前 N 部。
     */
    private List<PersonalizedDramaCard> hotCards(List<DramaRagExportDTO> catalog, int limit) {
        return toCards(hotDramas(catalog).stream().limit(limit).toList());
    }

    /**
     * 按现有首页热度公式排序。
     */
    private List<DramaRagExportDTO> hotDramas(List<DramaRagExportDTO> catalog) {
        return catalog.stream()
                .filter(drama -> drama != null && drama.id() != null)
                .sorted(Comparator.comparingDouble(this::popularity).reversed()
                        .thenComparing(drama -> drama.id(), Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    /**
     * 分类权重归一化到 0-1。
     */
    private Map<Long, Double> normalizeCategories(List<UserPreference.CategoryWeight> categories) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        double max = 0.0;
        for (UserPreference.CategoryWeight category : categories) {
            if (category != null && category.id() != null) {
                scores.put(category.id(), category.score());
                max = Math.max(max, category.score());
            }
        }
        if (max <= 0) {
            return Map.of();
        }
        double divisor = max;
        scores.replaceAll((id, score) -> score / divisor);
        return scores;
    }

    /**
     * 标签权重归一化到 0-1。
     */
    private Map<String, Double> normalizeTags(List<UserPreference.TagWeight> tags) {
        Map<String, Double> scores = new LinkedHashMap<>();
        double max = 0.0;
        for (UserPreference.TagWeight tag : tags) {
            if (tag != null && tag.name() != null && !tag.name().isBlank()) {
                scores.put(tag.name(), tag.score());
                max = Math.max(max, tag.score());
            }
        }
        if (max <= 0) {
            return Map.of();
        }
        double divisor = max;
        scores.replaceAll((name, score) -> score / divisor);
        return scores;
    }

    /**
     * 计算一部剧命中的标签权重。
     */
    private double tagScore(List<String> tags, Map<String, Double> tagScores) {
        if (tags == null || tags.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        for (String tag : tags) {
            if (tag != null) {
                score += tagScores.getOrDefault(tag, 0.0);
            }
        }
        return score;
    }

    /**
     * 热度公式与首页热播保持一致。
     */
    private double popularity(DramaRagExportDTO drama) {
        long view = drama.viewCount() == null ? 0 : drama.viewCount();
        long like = drama.likeCount() == null ? 0 : drama.likeCount();
        long favorite = drama.favoriteCount() == null ? 0 : drama.favoriteCount();
        long comment = drama.commentCount() == null ? 0 : drama.commentCount();
        return view + like * 3.0 + favorite * 5.0 + comment * 2.0;
    }

    /**
     * 判断是否 30 天内上新。
     */
    private boolean isFresh(String publishTime) {
        if (publishTime == null || publishTime.isBlank()) {
            return false;
        }
        try {
            return LocalDateTime.parse(publishTime).isAfter(LocalDateTime.now().minusDays(FRESH_DAYS));
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 目录 DTO 转前端卡片。
     */
    private List<PersonalizedDramaCard> toCards(List<DramaRagExportDTO> dramas) {
        return dramas.stream()
                .map(drama -> new PersonalizedDramaCard(
                        drama.id(),
                        drama.title(),
                        drama.subtitle(),
                        drama.coverUrl(),
                        drama.categoryName(),
                        drama.episodeCount(),
                        drama.viewCount(),
                        drama.likeCount()))
                .toList();
    }

    /**
     * 限制返回条数。
     */
    private int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    /**
     * 读取结果缓存。
     */
    private PersonalizedFeed readCache(String key) {
        if (key == null || System.currentTimeMillis() < redisUnavailableUntil) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(json, PersonalizedFeed.class);
        } catch (Exception ex) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("read personalized cache failed: key={}", key, ex);
            return null;
        }
    }

    /**
     * 写入结果缓存。
     */
    private void writeCache(String key, PersonalizedFeed feed) {
        if (key == null || System.currentTimeMillis() < redisUnavailableUntil) {
            return;
        }
        try {
            int minutes = Math.max(properties.getPersonalization().getCacheMinutes(), 1);
            String json = jsonMapper.writeValueAsString(feed);
            redissonClient.getBucket(key).set(json, Duration.ofMinutes(minutes));
        } catch (Exception ex) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("write personalized cache failed: key={}", key, ex);
        }
    }

    /**
     * 带分数的短剧。
     */
    private record ScoredDrama(DramaRagExportDTO drama, double score) {
    }
}
