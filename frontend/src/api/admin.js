import { request } from './http'

const BASE = '/api/drama/internal/admin/dramas'

/**
 * 分页查询审核队列。
 */
export function fetchReviewQueue({ reviewStatus, aiReviewStatus, pageNo, pageSize }) {
    return request(`${BASE}/reviews`, {
        query: { reviewStatus, aiReviewStatus, pageNo, pageSize }
    })
}

/**
 * 通过或驳回短剧。
 */
export function decideReview(dramaId, { decision, remark }) {
    return request(`${BASE}/${dramaId}/review`, {
        method: 'POST',
        body: { decision, remark }
    })
}

/**
 * 下架已上架短剧。
 */
export function offlineDrama(dramaId, remark = '') {
    return request(`${BASE}/${dramaId}/offline`, {
        method: 'POST',
        query: { remark }
    })
}

/**
 * 查询短剧审核操作日志。
 */
export function fetchReviewLogs(dramaId) {
    return request(`${BASE}/${dramaId}/logs`)
}

/**
 * 对单部待审短剧执行 AI 审核。
 */
export function auditDrama(dramaId) {
    return request(`/api/content/internal/ai/audit/dramas/${dramaId}`, {
        method: 'POST'
    })
}

/**
 * 触发 RAG 全量重建索引。
 */
export function reindexRag() {
    return request('/api/content/internal/ai/rag/reindex', {
        method: 'POST',
        body: {}
    })
}

/**
 * 管理端分页查询评论。
 */
export function fetchAdminComments({ status, dramaId, pageNo, pageSize }) {
    return request('/api/interaction/internal/admin/comments', {
        query: { status, dramaId, pageNo, pageSize }
    })
}

/**
 * 管理员隐藏评论。
 */
export function hideAdminComment(commentId) {
    return request(`/api/interaction/internal/admin/comments/${commentId}/hide`, {
        method: 'POST'
    })
}

/**
 * 管理员删除评论。
 */
export function deleteAdminComment(commentId) {
    return request(`/api/interaction/internal/admin/comments/${commentId}/delete`, {
        method: 'POST'
    })
}
