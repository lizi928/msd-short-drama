package com.msd.content.audit;

import com.msd.common.dto.DramaAiReviewResultRequest;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaReviewClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 短剧 AI 审核编排的通过、驳回、降级和写回测试。
 */
class DramaReviewAuditServiceTest {

    private final DramaReviewClient reviewClient = mock(DramaReviewClient.class);
    private final StructuredAuditService auditService = mock(StructuredAuditService.class);

    private final DramaReviewAuditService service =
            new DramaReviewAuditService(reviewClient, auditService, JsonMapper.shared());

    /**
     * 未违规写回 2，结果 JSON 包含原始结论。
     */
    @Test
    void writesPassedResult() {
        DramaReviewCandidateDTO candidate = candidate(5003L);
        when(reviewClient.detail(5003L)).thenReturn(Result.ok(candidate));
        when(auditService.audit(any())).thenReturn(new AuditCheckResponse(
                new AuditVerdict(false, "", List.of()), false, List.of(), 12));
        when(reviewClient.writeAiResult(eq(5003L), any())).thenReturn(Result.ok(true));

        DramaReviewAuditItem item = service.auditOne(5003L);

        assertThat(item.aiReviewStatus()).isEqualTo(2);
        assertThat(item.updated()).isTrue();

        ArgumentCaptor<DramaAiReviewResultRequest> captor =
                ArgumentCaptor.forClass(DramaAiReviewResultRequest.class);
        verify(reviewClient).writeAiResult(eq(5003L), captor.capture());
        assertThat(captor.getValue().aiReviewStatus()).isEqualTo(2);
        assertThat(captor.getValue().aiReviewResult()).contains("\"is_violation\":false");
        assertThat(captor.getValue().aiReviewResult()).contains("\"scene\":\"drama_description\"");
    }

    /**
     * 违规写回 3。
     */
    @Test
    void writesRejectedResult() {
        when(reviewClient.detail(5003L)).thenReturn(Result.ok(candidate(5003L)));
        when(auditService.audit(any())).thenReturn(new AuditCheckResponse(
                new AuditVerdict(true, "未成年人风险", List.of("minor")),
                false, List.of(), 20));
        when(reviewClient.writeAiResult(eq(5003L), any())).thenReturn(Result.ok(true));

        DramaReviewAuditItem item = service.auditOne(5003L);

        assertThat(item.aiReviewStatus()).isEqualTo(3);
        assertThat(item.updated()).isTrue();
    }

    /**
     * 降级写回 4，绝不能写通过。
     */
    @Test
    void degradedNeverPasses() {
        when(reviewClient.detail(5003L)).thenReturn(Result.ok(candidate(5003L)));
        when(auditService.audit(any())).thenReturn(new AuditCheckResponse(
                null, true, List.of(StructuredAuditService.FLAG_TIMEOUT), 10000));
        when(reviewClient.writeAiResult(eq(5003L), any())).thenReturn(Result.ok(true));

        DramaReviewAuditItem item = service.auditOne(5003L);

        assertThat(item.aiReviewStatus()).isEqualTo(4);
        assertThat(item.degradedFlags()).containsExactly(StructuredAuditService.FLAG_TIMEOUT);
        ArgumentCaptor<DramaAiReviewResultRequest> captor =
                ArgumentCaptor.forClass(DramaAiReviewResultRequest.class);
        verify(reviewClient).writeAiResult(eq(5003L), captor.capture());
        assertThat(captor.getValue().aiReviewStatus()).isEqualTo(4);
    }

    /**
     * 写回失败时 updated=false，批次计入 skipped。
     */
    @Test
    void marksSkippedWhenWritebackLosesRace() {
        when(reviewClient.pending(5)).thenReturn(Result.ok(List.of(candidate(5003L))));
        when(auditService.audit(any())).thenReturn(new AuditCheckResponse(
                new AuditVerdict(false, "", List.of()), false, List.of(), 12));
        when(reviewClient.writeAiResult(eq(5003L), any())).thenReturn(Result.ok(false));

        DramaReviewAuditBatchResult result = service.auditPending(5);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.audited()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.updated()).isFalse());
    }

    /**
     * drama 不可用时返回整体降级，不抛异常。
     */
    @Test
    void degradesWhenDramaUnavailable() {
        when(reviewClient.pending(5)).thenReturn(null);

        DramaReviewAuditBatchResult result = service.auditPending(5);

        assertThat(result.scanned()).isZero();
        assertThat(result.degradedFlags()).containsExactly("drama");
        verify(reviewClient, never()).writeAiResult(any(), any());
    }

    /**
     * 非待审短剧返回 null，不调用模型。
     */
    @Test
    void skipsWhenNotPending() {
        when(reviewClient.detail(5003L)).thenReturn(Result.ok(null));

        assertThat(service.auditOne(5003L)).isNull();
        verify(auditService, never()).audit(any());
    }

    /**
     * 构造待审短剧候选。
     */
    private DramaReviewCandidateDTO candidate(Long dramaId) {
        return new DramaReviewCandidateDTO(
                dramaId, "雾城谜案", "雨夜连环案", "刑警追查十年悬案。",
                "", "林一,苏晚", 1002L, 2003L, 3, 1, 1,
                "", "2026-10-25T12:00:00", null, null, null);
    }
}
