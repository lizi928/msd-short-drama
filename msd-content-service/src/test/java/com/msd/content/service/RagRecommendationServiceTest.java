package com.msd.content.service;

import com.msd.content.config.RagProperties;
import com.msd.content.dto.RagRecommendRequest;
import com.msd.content.dto.RagRecommendResponse;
import com.msd.content.dto.RagSource;
import com.msd.content.rag.RagMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG 推荐编排测试。
 */
class RagRecommendationServiceTest {

    private final RagProperties properties = new RagProperties();

    /**
     * 回答和来源应一起返回，来源包含精排分数、RRF 分数和两路排名。
     */
    @Test
    void returnsAnswerAndSources() {
        ChatClient chatClient = chatClient(response("《长安第一女仵作》——验尸破案。", List.of(document(5005))));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo("《长安第一女仵作》——验尸破案。");
        assertThat(result.sources()).hasSize(1);
        RagSource source = result.sources().get(0);
        assertThat(source.dramaId()).isEqualTo(5005L);
        assertThat(source.title()).isEqualTo("长安第一女仵作");
        assertThat(source.score()).isEqualTo(0.93);
        assertThat(source.rrfScore()).isEqualTo(0.0328);
        assertThat(source.vectorRank()).isEqualTo(1);
        assertThat(source.bm25Rank()).isEqualTo(3);
        assertThat(source.retrievalSources()).containsExactly("vector", "bm25");
        assertThat(result.degraded()).isFalse();
        assertThat(result.degradedFlags()).isEmpty();
    }

    /**
     * 没有检索结果时固定返回 no-match 文案，不能使用模型回答。
     */
    @Test
    void returnsFixedNoResultWhenNoDocuments() {
        ChatClient chatClient = chatClient(response("模型编造的回答", List.of()));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo(RagRecommendationService.NO_RESULT_ANSWER);
        assertThat(result.sources()).isEmpty();
        assertThat(result.degraded()).isFalse();
    }

    /**
     * 有来源但回答为空时返回生成失败兜底文案。
     */
    @Test
    void fallsBackWhenAnswerIsBlank() {
        ChatClient chatClient = chatClient(response("", List.of(document(5005))));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo(RagRecommendationService.GENERATION_FALLBACK);
        assertThat(result.sources()).hasSize(1);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedFlags()).containsExactly("generation");
    }

    /**
     * ChatClient 抛异常时仍返回 200 语义的兜底响应。
     */
    @Test
    void catchesChatClientFailure() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(anyString())
                .advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())
                .call()
                .chatClientResponse())
                .thenThrow(new RuntimeException("llm down"));

        RagRecommendationService service = new RagRecommendationService(chatClient, properties);
        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo(RagRecommendationService.NO_RESULT_ANSWER);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedFlags()).containsExactly("generation");
    }

    /**
     * 请求 topK 控制返回来源数量。
     */
    @Test
    void truncatesSourcesByRequestedTopK() {
        ChatClient chatClient = chatClient(response("answer", List.of(
                document(1), document(2), document(3), document(4))));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 2));

        assertThat(result.sources()).hasSize(2);
    }

    /**
     * 整体超时响应固定带 timeout 标记。
     */
    @Test
    void timeoutResponseCarriesFlag() {
        RagRecommendationService service = new RagRecommendationService(
                mock(ChatClient.class, RETURNS_DEEP_STUBS), properties);

        RagRecommendResponse result = service.timeoutResponse();

        assertThat(result.answer()).isEqualTo(RagRecommendationService.NO_RESULT_ANSWER);
        assertThat(result.sources()).isEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedFlags()).containsExactly("timeout");
        assertThat(result.elapsedMs()).isEqualTo(properties.getTotalTimeoutMs());
    }

    /**
     * 构造带文档上下文的 ChatClient 响应。
     */
    private ChatClientResponse response(String answer, List<Document> documents) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(answer))));
        Map<String, Object> context = documents.isEmpty()
                ? Map.of()
                : Map.of(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, documents);
        return new ChatClientResponse(chatResponse, context);
    }

    /**
     * 构造固定返回指定响应的 ChatClient。
     */
    private ChatClient chatClient(ChatClientResponse response) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(anyString())
                .advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())
                .call()
                .chatClientResponse())
                .thenReturn(response);
        return chatClient;
    }

    /**
     * 构造测试文档。
     */
    private Document document(long dramaId) {
        return Document.builder()
                .id("drama-" + dramaId)
                .text("标题：长安第一女仵作")
                .metadata(Map.of(
                        RagMetadata.DRAMA_ID, dramaId,
                        RagMetadata.TITLE, "长安第一女仵作",
                        RagMetadata.SUBTITLE, "验尸破案，顺便谈个恋爱",
                        RagMetadata.COVER_URL, "http://127.0.0.1:9000/msd/covers/5005.jpg",
                        RagMetadata.SNIPPET, "长安城接连出现离奇命案。",
                        RagMetadata.RERANK_SCORE, 0.93,
                        RagMetadata.RRF_SCORE, 0.0328,
                        RagMetadata.VECTOR_RANK, 1,
                        RagMetadata.BM25_RANK, 3,
                        RagMetadata.RETRIEVAL_SOURCES, List.of("vector", "bm25")))
                .score(0.93)
                .build();
    }
}
