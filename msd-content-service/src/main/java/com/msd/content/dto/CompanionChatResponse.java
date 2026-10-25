package com.msd.content.dto;

import java.util.List;

/**
 * AI 伴看聊天响应。
 *
 * @param reply         回复文本
 * @param sessionId     客户端会话标识
 * @param episodeId     当前剧集 ID
 * @param degraded      是否发生降级
 * @param degradedFlags 降级标记，如 summary、context、generation、memory_summary
 * @param elapsedMs     处理耗时（毫秒）
 */
public record CompanionChatResponse(
        String reply,
        String sessionId,
        Long episodeId,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public CompanionChatResponse {
        reply = reply == null ? "" : reply;
        sessionId = sessionId == null ? "" : sessionId;
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
