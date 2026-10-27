import { request } from './http'

/**
 * 获取首页个性化短剧推荐。
 *
 * @param {number} limit 返回条数
 * @returns {Promise<{items: Array, personalized: boolean, coldStart: boolean,
 *   degraded: boolean}>}
 */
export function fetchPersonalized(limit = 6) {
    return request('/api/content/internal/ai/rag/personalized', {
        query: { limit }
    })
}
