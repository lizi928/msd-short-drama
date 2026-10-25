import { request } from './http'

/**
 * 发送一条 AI 伴看消息。
 *
 * @param {{message: string, sessionId: string, episodeId: number}} payload 当前消息、会话和剧集
 * @returns {Promise<{reply: string, sessionId: string, episodeId: number,
 *   degraded: boolean, degradedFlags: string[], elapsedMs: number}>}
 */
export function chatWithCompanion(payload) {
    return request('/api/content/internal/ai/companion/chat', {
        method: 'POST',
        body: payload
    })
}
