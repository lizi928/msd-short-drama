<script setup>
import { computed, ref, watch } from 'vue'
import {
    auditDrama,
    decideReview,
    fetchReviewLogs,
    fetchReviewQueue,
    offlineDrama,
    reindexRag
} from '../api/admin'
import { usePagedList } from '../composables/usePagedList'
import { useToastStore } from '../stores/toast'
import { formatDateTime } from '../utils/format'
import AppModal from '../components/AppModal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const toast = useToastStore()

const reviewFilter = ref(1)
const auditingId = ref(0)
const reindexLoading = ref(false)
const decisionOpen = ref(false)
const decisionTarget = ref(null)
const decision = ref('APPROVE')
const remark = ref('')
const decisionLoading = ref(false)
const offlineTarget = ref(null)
const offlineOpen = ref(false)
const offlineLoading = ref(false)
const logsOpen = ref(false)
const logsTarget = ref(null)
const logs = ref([])
const logsLoading = ref(false)

const {
    items,
    total,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    refresh,
    loadMore
} = usePagedList(
    ({ pageNo, pageSize }) => fetchReviewQueue({
        reviewStatus: reviewFilter.value,
        pageNo,
        pageSize
    }),
    { keyOf: (item) => item.dramaId }
)

const STATUS_LABELS = {
    0: '草稿',
    1: '审核中',
    2: '已上架',
    3: '已驳回',
    4: '已下架'
}

const AI_LABELS = {
    0: 'AI 未提交',
    1: 'AI 待审',
    2: 'AI 通过',
    3: 'AI 驳回',
    4: 'AI 失败'
}

const filters = [
    { label: '待审', value: 1 },
    { label: '全部', value: null },
    { label: '草稿', value: 0 },
    { label: '已上架', value: 2 },
    { label: '已驳回', value: 3 },
    { label: '已下架', value: 4 }
]

const canSubmitDecision = computed(() => (
    !decisionLoading.value
    && (decision.value === 'APPROVE' || remark.value.trim().length > 0)
))

watch(reviewFilter, () => refresh())

function statusLabel(status) {
    return STATUS_LABELS[Number(status)] || '未知'
}

function aiLabel(status) {
    return AI_LABELS[Number(status)] || 'AI 未知'
}

/**
 * 把审核结果 JSON 转成简短可读文本。
 */
function aiSummary(item) {
    if (!item.aiReviewResult) {
        return '尚未执行 AI 审核'
    }
    try {
        const payload = JSON.parse(item.aiReviewResult)
        if (payload.degraded) {
            const flags = Array.isArray(payload.degradedFlags) ? payload.degradedFlags : []
            return `AI 降级：${flags.join('、') || '未知原因'}`
        }
        const verdict = payload.verdict
        if (!verdict) {
            return 'AI 未返回有效结论'
        }
        if (verdict.is_violation) {
            const tags = Array.isArray(verdict.tags) ? verdict.tags.join('、') : ''
            return `违规：${verdict.violation_reason || '未说明'}${tags ? `（${tags}）` : ''}`
        }
        return '未发现违规'
    } catch {
        return item.aiReviewResult.slice(0, 100)
    }
}

/**
 * 对单部待审短剧执行 AI 审核。
 */
async function runAudit(item) {
    if (auditingId.value) {
        return
    }
    auditingId.value = item.dramaId
    try {
        const result = await auditDrama(item.dramaId)
        if (!result) {
            toast.push('短剧不在待审状态')
        } else if (result.updated) {
            toast.success(`AI 审核完成：${aiLabel(result.aiReviewStatus)}`)
        } else {
            toast.push('AI 结果未写回，状态可能已变化')
        }
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || 'AI 审核失败')
    } finally {
        auditingId.value = 0
    }
}

function openDecision(item, value) {
    decisionTarget.value = item
    decision.value = value
    remark.value = ''
    decisionOpen.value = true
}

async function confirmDecision() {
    if (!decisionTarget.value || !canSubmitDecision.value) {
        return
    }
    decisionLoading.value = true
    try {
        await decideReview(decisionTarget.value.dramaId, {
            decision: decision.value,
            remark: remark.value.trim()
        })
        toast.success(decision.value === 'APPROVE' ? '已通过并上架' : '已驳回')
        decisionOpen.value = false
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '审核失败')
    } finally {
        decisionLoading.value = false
    }
}

/**
 * 查看某部短剧的审核日志。
 */
async function openLogs(item) {
    logsTarget.value = item
    logs.value = []
    logsOpen.value = true
    logsLoading.value = true
    try {
        logs.value = await fetchReviewLogs(item.dramaId)
    } catch (cause) {
        toast.push(cause?.message || '日志加载失败')
    } finally {
        logsLoading.value = false
    }
}

function openOffline(item) {
    offlineTarget.value = item
    offlineOpen.value = true
}

async function confirmOffline() {
    if (!offlineTarget.value || offlineLoading.value) {
        return
    }
    offlineLoading.value = true
    try {
        await offlineDrama(offlineTarget.value.dramaId, '审核台下架')
        toast.success('已下架')
        offlineOpen.value = false
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '下架失败')
    } finally {
        offlineLoading.value = false
    }
}

/**
 * 新剧上架后重建推荐索引。
 */
async function rebuildIndex() {
    if (reindexLoading.value) {
        return
    }
    reindexLoading.value = true
    try {
        const result = await reindexRag()
        toast.success(`索引已重建：${result.dramaCount} 部短剧 / ${result.episodeCount} 集`)
    } catch (cause) {
        toast.push(cause?.message || '索引重建失败')
    } finally {
        reindexLoading.value = false
    }
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="审核台" back-to="/me">
            <template #right>
                <button
                    class="btn btn-ghost btn-small"
                    type="button"
                    :disabled="reindexLoading"
                    @click="rebuildIndex"
                >
                    {{ reindexLoading ? '重建中…' : '重建索引' }}
                </button>
            </template>
        </PageHeader>

        <main class="app-main review-page">
            <div class="filter-strip" aria-label="审核状态筛选">
                <button
                    v-for="item in filters"
                    :key="String(item.value)"
                    class="chip"
                    :class="{ 'is-active': reviewFilter === item.value }"
                    type="button"
                    @click="reviewFilter = item.value"
                >
                    {{ item.label }}
                </button>
            </div>

            <div v-if="loading" class="card-list">
                <SkeletonBlock v-for="index in 4" :key="index" height="150px" radius="16px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <EmptyState
                v-else-if="!items.length"
                title="当前没有待处理内容"
                description="切换状态筛选查看其他短剧。"
            />

            <template v-else>
                <div class="card-list">
                    <article v-for="item in items" :key="item.dramaId" class="review-card">
                        <div class="review-head">
                            <div class="review-copy">
                                <h2>{{ item.title }}</h2>
                                <p>{{ item.subtitle || item.description || '暂无简介' }}</p>
                            </div>
                            <span class="status-chip" :class="`status-${item.reviewStatus}`">
                                {{ statusLabel(item.reviewStatus) }}
                            </span>
                        </div>

                        <div class="review-meta">
                            <span>作者 {{ item.authorId }}</span>
                            <span>{{ aiLabel(item.aiReviewStatus) }}</span>
                            <span>{{ item.submitTime ? formatDateTime(item.submitTime) : '未提交' }}</span>
                        </div>

                        <p class="ai-summary" :class="{ degraded: item.aiReviewStatus === 4 }">
                            {{ aiSummary(item) }}
                        </p>

                        <div class="review-actions">
                            <button
                                v-if="Number(item.reviewStatus) === 1"
                                class="btn btn-ghost btn-small"
                                type="button"
                                :disabled="auditingId === item.dramaId"
                                @click="runAudit(item)"
                            >
                                {{ auditingId === item.dramaId ? '审核中…' : 'AI 审核' }}
                            </button>
                            <button
                                v-if="Number(item.reviewStatus) === 1"
                                class="btn btn-primary btn-small"
                                type="button"
                                @click="openDecision(item, 'APPROVE')"
                            >
                                通过
                            </button>
                            <button
                                v-if="Number(item.reviewStatus) === 1"
                                class="btn btn-danger btn-small"
                                type="button"
                                @click="openDecision(item, 'REJECT')"
                            >
                                驳回
                            </button>
                            <button
                                v-if="Number(item.reviewStatus) === 2"
                                class="btn btn-ghost btn-small"
                                type="button"
                                @click="openOffline(item)"
                            >
                                下架
                            </button>
                            <button
                                class="btn btn-ghost btn-small"
                                type="button"
                                @click="openLogs(item)"
                            >
                                日志
                            </button>
                        </div>
                    </article>
                </div>

                <LoadMore
                    :loading="loadingMore"
                    :has-more="hasMore"
                    :error="loadMoreError"
                    :total="total"
                    @load="loadMore"
                />
            </template>
        </main>

        <AppModal v-model="decisionOpen" size="lg" :title="decision === 'APPROVE' ? '通过审核' : '驳回短剧'">
            <form class="decision-form" @submit.prevent="confirmDecision">
                <p class="decision-title">
                    {{ decisionTarget?.title }}
                </p>
                <label class="field">
                    <span>审核意见{{ decision === 'REJECT' ? '（必填）' : '（可选）' }}</span>
                    <textarea
                        v-model="remark"
                        maxlength="500"
                        rows="4"
                        :placeholder="decision === 'APPROVE' ? '可补充人工复核说明' : '说明驳回原因'"
                    ></textarea>
                </label>
                <button class="btn btn-primary btn-block" type="submit" :disabled="!canSubmitDecision">
                    {{ decisionLoading ? '提交中…' : (decision === 'APPROVE' ? '确认通过并上架' : '确认驳回') }}
                </button>
            </form>
        </AppModal>

        <AppModal v-model="logsOpen" size="lg" title="审核日志">
            <div v-if="logsLoading" class="log-list">
                <SkeletonBlock v-for="index in 3" :key="index" height="54px" radius="10px"/>
            </div>
            <p v-else-if="!logs.length" class="log-empty">暂无审核日志</p>
            <div v-else class="log-list">
                <div v-for="log in logs" :key="log.id" class="log-row">
                    <div class="log-head">
                        <strong>{{ log.action }}</strong>
                        <span>{{ log.source === 'AI' ? 'AI' : '人工' }}</span>
                    </div>
                    <p>
                        状态 {{ log.fromStatus ?? '—' }} → {{ log.toStatus ?? '—' }}
                        <template v-if="log.operatorId">· 操作人 {{ log.operatorId }}</template>
                    </p>
                    <p v-if="log.remark">{{ log.remark }}</p>
                    <time>{{ formatDateTime(log.createTime) }}</time>
                </div>
            </div>
        </AppModal>

        <ConfirmDialog
            v-model="offlineOpen"
            title="下架短剧"
            :message="offlineTarget ? `确认下架《${offlineTarget.title}》？下架后首页和详情不再可见。` : ''"
            confirm-text="下架"
            danger
            :loading="offlineLoading"
            @confirm="confirmOffline"
        />
    </div>
</template>

<style scoped>
.review-page {
    display: grid;
    gap: 12px;
}

.filter-strip {
    display: flex;
    gap: 8px;
    overflow-x: auto;
    padding-bottom: 2px;
    scrollbar-width: none;
}

.filter-strip::-webkit-scrollbar {
    display: none;
}

.filter-strip .chip {
    flex: none;
}

.card-list {
    display: grid;
    gap: 12px;
}

.review-card {
    display: grid;
    gap: 9px;
    padding: 15px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
}

.review-head {
    display: flex;
    align-items: flex-start;
    gap: 10px;
}

.review-copy {
    min-width: 0;
    flex: 1;
}

.review-copy h2 {
    overflow: hidden;
    margin: 0 0 4px;
    font-size: 16px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.review-copy p {
    display: -webkit-box;
    overflow: hidden;
    margin: 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.status-chip {
    flex: none;
    padding: 3px 9px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--muted);
    font-size: 10px;
}

.status-1 {
    border-color: rgba(255, 179, 63, 0.45);
    color: var(--brand-2);
}

.status-2 {
    border-color: rgba(71, 209, 140, 0.45);
    color: var(--ok);
}

.status-3 {
    border-color: rgba(255, 92, 61, 0.45);
    color: var(--danger);
}

.review-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    color: rgba(255, 255, 255, 0.36);
    font-size: 10px;
}

.ai-summary {
    margin: 0;
    padding: 8px 10px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.025);
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.ai-summary.degraded {
    border-color: rgba(255, 92, 61, 0.3);
    color: var(--brand-2);
}

.review-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

.decision-form {
    display: grid;
    gap: 12px;
}

.decision-title {
    margin: 0;
    color: var(--text);
    font-size: 15px;
    font-weight: 700;
}

.log-list {
    display: grid;
    gap: 8px;
}

.log-row {
    display: grid;
    gap: 3px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.025);
}

.log-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    font-size: 11px;
}

.log-head strong {
    color: var(--brand-2);
}

.log-head span {
    color: var(--muted);
}

.log-row p {
    margin: 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.5;
}

.log-row time {
    color: rgba(255, 255, 255, 0.3);
    font-size: 10px;
}

.log-empty {
    margin: 0;
    color: var(--muted);
    font-size: 12px;
}

/* ---------- 桌面端（≥1024）：一行一条审核记录 ---------- */

@media (min-width: 1024px) {
    .review-page {
        gap: 14px;
    }

    .filter-strip {
        flex-wrap: wrap;
        overflow: visible;
    }

    .card-list {
        gap: 10px;
    }

    .review-card {
        grid-template-columns: minmax(260px, 300px) minmax(0, 1fr) auto;
        align-items: center;
        column-gap: 24px;
        row-gap: 6px;
        padding: 14px 20px;
    }

    .review-head {
        grid-column: 1;
        grid-row: 1;
        align-items: center;
    }

    .review-copy h2 {
        margin-bottom: 0;
        font-size: 15px;
    }

    .review-copy p {
        display: none;
    }

    .review-meta {
        grid-column: 1;
        grid-row: 2;
        flex-wrap: nowrap;
    }

    .ai-summary {
        grid-column: 2;
        grid-row: 1 / span 2;
    }

    .review-actions {
        grid-column: 3;
        grid-row: 1 / span 2;
        flex-wrap: nowrap;
        justify-content: flex-end;
    }
}
</style>
