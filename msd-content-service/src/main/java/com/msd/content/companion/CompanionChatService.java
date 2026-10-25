package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCompanionClient;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.CompanionChatRequest;
import com.msd.content.dto.CompanionChatResponse;
import com.msd.content.dto.CompanionContextView;
import com.msd.content.dto.ContextItem;
import com.msd.content.dto.EpisodeSummaryView;
import com.msd.content.memory.SummarizingChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AI 伴看聊天编排。
 *
 * <p>每轮注入当前集摘要和前情资料，带上会话记忆；任何依赖失败都降级回复，不把异常抛给前端。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionChatService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CompanionChatService.class);

    /** 生成失败时的固定回复。 */
    static final String GENERATION_FALLBACK =
            "抱歉，我暂时没能想好怎么回答，可以换个说法再聊聊这一集。";
    /** 剧集不可见时的固定回复。 */
    static final String NOT_FOUND_REPLY = "没有找到这一集，可能已经下线了。";
    /** 剧集服务不可用时的固定回复。 */
    static final String DRAMA_UNAVAILABLE_REPLY = "暂时无法读取剧集信息，稍后再试试。";
    /** 整体超时时的固定回复。 */
    static final String TIMEOUT_REPLY = "抱歉，这个回答花的时间有点久，可以再问我一次。";

    // 短剧内部接口客户端
    private final DramaCompanionClient dramaClient;
    // 分集摘要服务
    private final EpisodeSummaryService summaryService;
    // 前情检索服务
    private final CompanionContextService contextService;
    // 会话记忆
    private final SummarizingChatMemory chatMemory;
    // 伴看 ChatClient，已挂记忆 Advisor
    private final ChatClient companionChatClient;
    // 伴看配置
    private final CompanionProperties properties;

    /**
     * 注入聊天链路依赖和伴看 ChatClient。
     */
    public CompanionChatService(DramaCompanionClient dramaClient,
                                EpisodeSummaryService summaryService,
                                CompanionContextService contextService,
                                SummarizingChatMemory chatMemory,
                                @Qualifier("companionChatClient") ChatClient companionChatClient,
                                CompanionProperties properties) {
        this.dramaClient = dramaClient;
        this.summaryService = summaryService;
        this.contextService = contextService;
        this.chatMemory = chatMemory;
        this.companionChatClient = companionChatClient;
        this.properties = properties;
    }

    /**
     * 处理一轮伴看聊天。
     *
     * @param userId  网关注入的用户 ID
     * @param request 聊天请求
     */
    public CompanionChatResponse chat(Long userId, CompanionChatRequest request) {
        long startedAt = System.currentTimeMillis();
        Set<String> flags = new LinkedHashSet<>();

        // 1. 读取当前剧集元信息，区分不可见和下游不可用
        CompanionEpisodeDTO meta;
        try {
            Result<CompanionEpisodeDTO> response = dramaClient.companion(request.episodeId());
            if (response == null || !response.isSuccess()) {
                flags.add(CompanionContextService.FLAG_DRAMA);
                return response(request, DRAMA_UNAVAILABLE_REPLY, flags, startedAt);
            }
            meta = response.getData();
        } catch (Exception e) {
            log.warn("companion chat lookup failed: episodeId={}, error={}",
                    request.episodeId(), rootMessage(e));
            flags.add(CompanionContextService.FLAG_DRAMA);
            return response(request, DRAMA_UNAVAILABLE_REPLY, flags, startedAt);
        }
        if (meta == null) {
            flags.add("not_found");
            return response(request, NOT_FOUND_REPLY, flags, startedAt);
        }

        // 2. 当前集摘要，失败只标记降级
        EpisodeSummaryView summary = summaryService.getSummary(meta);
        if (summary == null || summary.degraded()) {
            flags.add("summary");
        }

        // 3. 当前集之前的前情，失败只标记降级
        CompanionContextView context = contextService.retrieve(
                request.message(), meta, properties.getContext().getDefaultTopK());
        if (context != null && context.degraded()) {
            flags.add("context");
        }

        // 4. 会话 ID 包含用户、短剧和客户端会话，换集仍保持连续
        String conversationId = "u" + userId + ":d" + meta.dramaId()
                + ":s" + request.sessionId();
        String systemPrompt = buildSystemPrompt(meta, summary, context);

        // 5. 调用带记忆 Advisor 的 ChatClient
        String reply = null;
        try {
            reply = companionChatClient.prompt()
                    .system(systemPrompt)
                    .user(request.message())
                    .advisors(advisor -> advisor.param(
                            ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("companion chat generation failed: episodeId={}, error={}",
                    meta.episodeId(), rootMessage(e));
            flags.add("generation");
        }
        if (reply == null || reply.isBlank()) {
            flags.add("generation");
            reply = GENERATION_FALLBACK;
        }

        // 6. 收集记忆压缩/Redis 降级标记
        flags.addAll(chatMemory.drainDegradedFlags(conversationId));

        log.info("companion chat: userId={}, episodeId={}, replyChars={}, flags={}, elapsedMs={}",
                userId, meta.episodeId(), reply.length(), flags,
                System.currentTimeMillis() - startedAt);
        return response(request, reply, flags, startedAt);
    }

    /**
     * 整体超时后的固定响应。
     */
    public CompanionChatResponse timeoutResponse(CompanionChatRequest request) {
        return new CompanionChatResponse(
                TIMEOUT_REPLY, request.sessionId(), request.episodeId(),
                true, java.util.List.of("timeout"), properties.getChat().getTimeoutMs());
    }

    /**
     * 构造带当前集摘要和前情资料的系统提示。
     */
    private String buildSystemPrompt(CompanionEpisodeDTO meta,
                                     EpisodeSummaryView summary,
                                     CompanionContextView context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是「码上短剧」的 AI 伴看助手，只陪用户讨论当前正在观看的这一集及其之前的情节。\n\n");

        // 1. 当前剧集事实
        prompt.append("【当前剧集】\n")
                .append("剧名：").append(nullToEmpty(meta.dramaTitle())).append('\n')
                .append("集数：第").append(meta.episodeNo()).append("集 ")
                .append(nullToEmpty(meta.episodeTitle())).append('\n');
        if (summary != null && hasText(summary.summary())) {
            prompt.append("当前集摘要：").append(summary.summary()).append('\n');
        } else {
            prompt.append("当前集摘要：暂不可用\n");
        }

        // 2. 当前集之前的前情
        prompt.append("\n【前情资料】\n");
        if (context != null && !context.items().isEmpty()) {
            int index = 1;
            for (ContextItem item : context.items()) {
                prompt.append('[').append(index++).append("] 第")
                        .append(item.episodeNo()).append("集 ")
                        .append(nullToEmpty(item.episodeTitle())).append("：")
                        .append(nullToEmpty(item.summary())).append('\n');
            }
        } else {
            prompt.append("没有检索到当前集之前的前情。\n");
        }

        // 3. 防剧透和输出要求
        prompt.append("\n【规则】\n")
                .append("1. 只能使用当前集摘要和前情资料回答，不得编造资料之外的信息。\n")
                .append("2. 严禁提及、猜测或暗示第").append(meta.episodeNo())
                .append("集之后的任何剧情、结局或反转。\n")
                .append("3. 用户追问后续时，统一回复：“为了不剧透，我只陪你看到当前这一集。”\n")
                .append("4. 回答 2-4 句，中文口语化；先回应情绪，再给有依据的信息。\n")
                .append("5. 资料不足时直接说明，不要编造。\n");
        return prompt.toString();
    }

    /**
     * 组装响应并记录耗时。
     */
    private CompanionChatResponse response(CompanionChatRequest request, String reply,
                                           Set<String> flags, long startedAt) {
        return new CompanionChatResponse(
                reply,
                request.sessionId(),
                request.episodeId(),
                !flags.isEmpty(),
                java.util.List.copyOf(flags),
                System.currentTimeMillis() - startedAt);
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * null 转为空字符串。
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 取最内层异常信息。
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
