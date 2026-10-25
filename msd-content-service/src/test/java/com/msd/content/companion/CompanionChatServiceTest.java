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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

/**
 * AI 伴看聊天编排与降级测试。
 */
class CompanionChatServiceTest {

    private final DramaCompanionClient dramaClient = mock(DramaCompanionClient.class);
    private final EpisodeSummaryService summaryService = mock(EpisodeSummaryService.class);
    private final CompanionContextService contextService = mock(CompanionContextService.class);
    private final SummarizingChatMemory chatMemory = mock(SummarizingChatMemory.class);
    private final ChatClient chatClient = mock(ChatClient.class,
            withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
    private final CompanionProperties properties = new CompanionProperties();

    private final CompanionChatService service = new CompanionChatService(
            dramaClient, summaryService, contextService, chatMemory, chatClient, properties);

    private final CompanionChatRequest request =
            new CompanionChatRequest("他们为什么吵架", "s1", 6005L);

    /**
     * 正常聊天返回模型回复，摘要和前情都注入成功。
     */
    @Test
    void returnsReplyWithSummaryAndContext() {
        CompanionEpisodeDTO meta = meta();
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(meta));
        when(summaryService.getSummary(meta)).thenReturn(
                new EpisodeSummaryView(6005L, 5001L, 5, "第5集 告白",
                        "隐婚总裁的甜妻", "当前集摘要", "seed", false, List.of()));
        when(contextService.retrieve("他们为什么吵架", meta, 3)).thenReturn(context(false));
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(Consumer.class)).call().content()).thenReturn("回复内容");
        when(chatMemory.drainDegradedFlags("u1003:d5001:ss1")).thenReturn(List.of());

        CompanionChatResponse response = service.chat(1003L, request);

        assertThat(response.reply()).isEqualTo("回复内容");
        assertThat(response.sessionId()).isEqualTo("s1");
        assertThat(response.degraded()).isFalse();
        verify(summaryService).getSummary(meta);
        verify(contextService).retrieve("他们为什么吵架", meta, 3);
        org.mockito.ArgumentCaptor<String> idCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chatMemory).drainDegradedFlags(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo("u1003:d5001:ss1");
    }

    /**
     * 摘要和前情降级时只标记对应 flag，聊天仍返回。
     */
    @Test
    void marksSummaryAndContextFlags() {
        CompanionEpisodeDTO meta = meta();
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(meta));
        when(summaryService.getSummary(meta)).thenReturn(
                new EpisodeSummaryView(6005L, 5001L, 5, "第5集 告白",
                        "隐婚总裁的甜妻", "", "missing", true, List.of("summary_model")));
        when(contextService.retrieve(anyString(), eq(meta), eq(3))).thenReturn(context(true));
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(Consumer.class)).call().content()).thenReturn("回复内容");
        when(chatMemory.drainDegradedFlags("u1003:d5001:ss1")).thenReturn(List.of());

        CompanionChatResponse response = service.chat(1003L, request);

        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags()).containsExactly("summary", "context");
    }

    /**
     * 模型返回空文本时使用固定兜底文案并标记 generation。
     */
    @Test
    void marksGenerationWhenReplyBlank() {
        CompanionEpisodeDTO meta = meta();
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(meta));
        when(summaryService.getSummary(meta)).thenReturn(summary());
        when(contextService.retrieve(anyString(), eq(meta), eq(3))).thenReturn(context(false));
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(Consumer.class)).call().content()).thenReturn("  ");
        when(chatMemory.drainDegradedFlags(anyString())).thenReturn(List.of());

        CompanionChatResponse response = service.chat(1003L, request);

        assertThat(response.reply()).isEqualTo(CompanionChatService.GENERATION_FALLBACK);
        assertThat(response.degradedFlags()).containsExactly("generation");
    }

    /**
     * 记忆压缩降级标记会合并到响应。
     */
    @Test
    void addsMemoryFlags() {
        CompanionEpisodeDTO meta = meta();
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(meta));
        when(summaryService.getSummary(meta)).thenReturn(summary());
        when(contextService.retrieve(anyString(), eq(meta), eq(3))).thenReturn(context(false));
        when(chatClient.prompt().system(anyString()).user(anyString())
                .advisors(any(Consumer.class)).call().content()).thenReturn("回复内容");
        when(chatMemory.drainDegradedFlags("u1003:d5001:ss1"))
                .thenReturn(List.of(SummarizingChatMemory.FLAG_MEMORY_SUMMARY));

        CompanionChatResponse response = service.chat(1003L, request);

        assertThat(response.degradedFlags()).containsExactly("memory_summary");
    }

    /**
     * 剧集不可见时返回固定下架文案。
     */
    @Test
    void returnsNotFoundWhenEpisodeInvisible() {
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(null));

        CompanionChatResponse response = service.chat(1003L, request);

        assertThat(response.reply()).isEqualTo(CompanionChatService.NOT_FOUND_REPLY);
        assertThat(response.degradedFlags()).containsExactly("not_found");
    }

    /**
     * drama 不可用时返回固定兜底文案并标记 drama。
     */
    @Test
    void degradesWhenDramaUnavailable() {
        when(dramaClient.companion(6005L)).thenThrow(new IllegalStateException("drama down"));

        CompanionChatResponse response = service.chat(1003L, request);

        assertThat(response.reply()).isEqualTo(CompanionChatService.DRAMA_UNAVAILABLE_REPLY);
        assertThat(response.degradedFlags())
                .containsExactly(CompanionContextService.FLAG_DRAMA);
    }

    /**
     * 构造剧集元信息。
     */
    private CompanionEpisodeDTO meta() {
        return new CompanionEpisodeDTO(
                6005L, 5001L, "隐婚总裁的甜妻", 5, "第5集 告白",
                "", 2, "简介");
    }

    /**
     * 构造正常摘要视图。
     */
    private EpisodeSummaryView summary() {
        return new EpisodeSummaryView(6005L, 5001L, 5, "第5集 告白",
                "隐婚总裁的甜妻", "当前集摘要", "seed", false, List.of());
    }

    /**
     * 构造前情视图。
     */
    private CompanionContextView context(boolean degraded) {
        List<ContextItem> items = List.of(new ContextItem(
                6003L, 3, "第3集 危机", "第三集摘要", 0.8, List.of("vector")));
        return new CompanionContextView(6005L, 5001L, 5, items, degraded,
                degraded ? List.of("vector") : List.of(), 10);
    }
}
