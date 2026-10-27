package com.msd.content.service;

import com.msd.content.config.RagProperties;
import com.msd.content.dto.RagRecommendRequest;
import com.msd.content.dto.RagRecommendResponse;
import com.msd.content.dto.RagSource;
import com.msd.content.dto.UserPreference;
import com.msd.content.rag.RagMetadata;
import com.msd.content.rag.RagTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 推荐编排：调用带 RetrievalAugmentationAdvisor 的 ChatClient，并组装来源。
 */
public class RagRecommendationService {

    // 日志
    private static final Logger log = LoggerFactory.getLogger(RagRecommendationService.class);

    /** 没有检索到任何短剧时的固定文案。 */
    public static final String NO_RESULT_ANSWER =
            "抱歉，暂时没有找到符合你描述的短剧，可以换一个说法再试试。";

    /** 有候选但生成失败时的固定文案。 */
    public static final String GENERATION_FALLBACK =
            "这几部短剧和你的需求比较接近，但暂时无法生成推荐理由，可以点开详情看看。";

    /** 单次推荐最多返回的短剧条数。 */
    private static final int MAX_TOP_K = 3;
    /** 正文摘要的截断长度（字符）。 */
    private static final int SNIPPET_CHARS = 120;

    // RAG 专用 ChatClient，已挂载检索增强 advisor
    private final ChatClient ragChatClient;
    // RAG 超时、rerank 等配置
    private final RagProperties properties;
    // 用户偏好服务，测试中可为 null
    private final UserPreferenceService preferenceService;

    /**
     * 兼容旧调用方的构造器，不加载用户偏好。
     */
    public RagRecommendationService(ChatClient ragChatClient, RagProperties properties) {
        this(ragChatClient, properties, null);
    }

    /**
     * 注入 RAG ChatClient、配置和偏好服务。
     */
    public RagRecommendationService(ChatClient ragChatClient, RagProperties properties,
                                    UserPreferenceService preferenceService) {
        this.ragChatClient = ragChatClient;
        this.properties = properties;
        this.preferenceService = preferenceService;
    }

    /**
     * 执行一次不携带用户上下文的推荐。
     */
    public RagRecommendResponse recommend(RagRecommendRequest request) {
        return recommend(request, null);
    }

    /**
     * 执行一次推荐，可选加载用户偏好。
     */
    public RagRecommendResponse recommend(RagRecommendRequest request, Long userId) {
        String query = request.query().trim();
        int topK = resolveTopK(request.topK());

        // 1. trace 通过 advisor param 进入 Query.context，后续改写/检索/精排共享
        RagTrace trace = new RagTrace(query);
        trace.setRequestedTopK(topK);
        // 2. 可选加载画像，把偏好检索词交给多查询扩展器
        loadPreference(userId, trace);

        try {
            ChatClientResponse response = ragChatClient.prompt()
                    .user(query)
                    .advisors(advisor -> advisor.param(RagTrace.CONTEXT_KEY, trace))
                    .call()
                    .chatClientResponse();

            String answer = extractAnswer(response);
            List<Document> documents = trace.getFinalDocuments();
            if (documents.isEmpty()) {
                documents = documentsFromResponse(response);
            }

            // 2. 没有检索到文档时固定返回 no-match，避免模型编造
            if (documents.isEmpty()) {
                return noResult(trace);
            }

            // 3. 生成失败但检索成功时保留来源，返回固定兜底文案
            if (answer == null || answer.isBlank()) {
                trace.markDegraded("generation");
                answer = GENERATION_FALLBACK;
            } else {
                answer = answer.trim();
            }
            return toResponse(answer, trace, documents, topK);
        } catch (Exception e) {
            log.error("rag recommend failed: query={}", query, e);
            trace.markDegraded("generation");
            List<Document> documents = trace.getFinalDocuments();
            if (documents.isEmpty()) {
                return noResult(trace);
            }
            return toResponse(GENERATION_FALLBACK, trace, documents, topK);
        }
    }

    /**
     * 加载用户偏好；未登录、未开启或依赖失败时静默跳过。
     */
    private void loadPreference(Long userId, RagTrace trace) {
        // 1. 未登录、未开启或测试环境未注入偏好服务
        if (userId == null || userId <= 0 || preferenceService == null
                || !properties.getPersonalization().isEnabled()) {
            return;
        }
        try {
            // 2. 查询画像并写入 trace，供多查询扩展器读取
            UserPreference preference = preferenceService.get(userId);
            trace.setUserId(userId);
            trace.setPersonalizationEmpty(preference.empty());
            trace.setPreferenceQuery(preference.preferenceQuery());
            if (preference.degraded()) {
                trace.markDegraded("personalization");
            }
        } catch (Exception ex) {
            // 3. 画像失败不阻断推荐，只标记降级
            trace.markDegraded("personalization");
            log.warn("load user preference failed: userId={}", userId, ex);
        }
    }

    /**
     * 整体超时后的固定响应。
     */
    public RagRecommendResponse timeoutResponse() {
        return new RagRecommendResponse(
                NO_RESULT_ANSWER,
                null,
                List.of(),
                true,
                List.of("timeout"),
                properties.getTotalTimeoutMs());
    }

    /**
     * 校验并收敛 topK；请求未传时使用 rerank.top-n。
     */
    private int resolveTopK(Integer requested) {
        int topK = requested == null ? properties.getRerank().getTopN() : requested;
        return Math.max(1, Math.min(topK, MAX_TOP_K));
    }

    /**
     * 从 ChatClientResponse 提取回答文本。
     */
    private String extractAnswer(ChatClientResponse response) {
        // 1. 响应或模型响应缺失时没有可提取的内容
        if (response == null || response.chatResponse() == null) {
            return null;
        }
        ChatResponse chatResponse = response.chatResponse();
        // 2. 输出节点缺失时同样返回 null
        if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        // 3. 取第一条输出的文本
        return chatResponse.getResult().getOutput().getText();
    }

    /**
     * 从 advisor 响应上下文兜底读取最终文档。
     */
    private List<Document> documentsFromResponse(ChatClientResponse response) {
        // 1. 响应上下文为空时视为没有文档
        if (response == null || response.context() == null) {
            return List.of();
        }
        // 2. advisor 把最终文档放在 DOCUMENT_CONTEXT 键下
        Object value = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        // 3. 过滤非 Document 元素后返回不可变列表
        List<Document> documents = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Document document) {
                documents.add(document);
            }
        }
        return List.copyOf(documents);
    }

    /**
     * 组装正常响应。
     */
    private RagRecommendResponse toResponse(String answer, RagTrace trace,
                                            List<Document> documents, int topK) {
        // 1. 截断到 topK 并逐条转成来源卡片
        List<RagSource> sources = documents.stream()
                .limit(topK)
                .map(this::toSource)
                .toList();
        // 2. 组装回答、改写查询和降级信息
        return new RagRecommendResponse(
                answer,
                trace.getRewrittenQuery(),
                sources,
                trace.isDegraded(),
                trace.getDegradedFlags(),
                elapsed(trace));
    }

    /**
     * 无检索结果响应。
     */
    private RagRecommendResponse noResult(RagTrace trace) {
        return new RagRecommendResponse(
                NO_RESULT_ANSWER,
                trace.getRewrittenQuery(),
                List.of(),
                trace.isDegraded(),
                trace.getDegradedFlags(),
                elapsed(trace));
    }

    /**
     * Document 转来源卡片。
     */
    private RagSource toSource(Document document) {
        // metadata 字段逐个转换，缺失时使用 0 / null / 空串等默认值
        Map<String, Object> metadata = document.getMetadata();
        return new RagSource(
                longValue(metadata.get(RagMetadata.DRAMA_ID)),
                stringValue(metadata.get(RagMetadata.TITLE)),
                stringValue(metadata.get(RagMetadata.SUBTITLE)),
                stringValue(metadata.get(RagMetadata.COVER_URL)),
                snippet(document),
                firstDouble(
                        metadata.get(RagMetadata.RERANK_SCORE),
                        metadata.get(RagMetadata.RRF_SCORE),
                        document.getScore()),
                doubleValue(metadata.get(RagMetadata.RRF_SCORE)),
                integerValue(metadata.get(RagMetadata.VECTOR_RANK)),
                integerValue(metadata.get(RagMetadata.BM25_RANK)),
                retrievalSources(metadata));
    }

    /**
     * 优先取 metadata 摘要，缺失时截断正文。
     */
    private String snippet(Document document) {
        // 1. 优先使用 metadata 中预生成的摘要
        String snippet = stringValue(document.getMetadata().get(RagMetadata.SNIPPET));
        if (!snippet.isBlank()) {
            return snippet;
        }
        // 2. 摘要缺失时截断正文作为兜底
        String text = document.getText() == null ? "" : document.getText();
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS);
    }

    /**
     * 读取两路检索来源。
     */
    private List<String> retrievalSources(Map<String, Object> metadata) {
        // 1. 优先读取多路检索来源列表
        Object value = metadata.get(RagMetadata.RETRIEVAL_SOURCES);
        if (value instanceof List<?> values) {
            List<String> sources = new ArrayList<>();
            for (Object item : values) {
                String source = stringValue(item);
                if (!source.isBlank()) {
                    sources.add(source);
                }
            }
            if (!sources.isEmpty()) {
                return List.copyOf(sources);
            }
        }
        // 2. 兼容只记录单值来源的旧数据
        String source = stringValue(metadata.get(RagMetadata.RETRIEVAL_SOURCE));
        return source.isBlank() ? List.of() : List.of(source);
    }

    /**
     * 取第一个非空 Double。
     */
    private Double firstDouble(Object... values) {
        // 按传入顺序返回第一个可解析的数值
        for (Object value : values) {
            Double number = doubleValue(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    /**
     * 任意对象转 Long，无法转换时返回 0。
     */
    private Long longValue(Object value) {
        // 1. Number 类型直接取 long 值
        if (value instanceof Number number) {
            return number.longValue();
        }
        // 2. 字符串尝试解析，失败回退 0
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 任意对象转 Integer，无法转换时返回 null。
     */
    private Integer integerValue(Object value) {
        // 1. Number 类型直接取 int 值
        if (value instanceof Number number) {
            return number.intValue();
        }
        // 2. 字符串尝试解析，失败返回 null
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 任意对象转 Double，无法转换时返回 null。
     */
    private Double doubleValue(Object value) {
        // 1. Number 类型直接取 double 值
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        // 2. 字符串尝试解析，失败返回 null
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 任意对象转字符串，null 返回空字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 计算耗时。
     */
    private long elapsed(RagTrace trace) {
        return Math.max(System.currentTimeMillis() - trace.getStartedAt(), 0);
    }
}
