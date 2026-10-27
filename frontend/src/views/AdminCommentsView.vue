<script setup>
import { ref, watch } from 'vue'
import {
    deleteAdminComment,
    fetchAdminComments,
    hideAdminComment
} from '../api/admin'
import { usePagedList } from '../composables/usePagedList'
import { useToastStore } from '../stores/toast'
import { formatDateTime } from '../utils/format'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const toast = useToastStore()

const statusFilter = ref(1)
const pendingId = ref(0)
const deleteTarget = ref(null)
const deleteOpen = ref(false)
const deleteLoading = ref(false)

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
    ({ pageNo, pageSize }) => fetchAdminComments({
        status: statusFilter.value,
        pageNo,
        pageSize
    }),
    { keyOf: (item) => item.commentId }
)

const filters = [
    { label: '可见', value: 1 },
    { label: '待审', value: 0 },
    { label: '已隐藏', value: 2 },
    { label: '已删除', value: 3 },
    { label: '全部', value: null }
]

const STATUS_LABELS = {
    0: '待审',
    1: '可见',
    2: '已隐藏',
    3: '已删除'
}

watch(statusFilter, () => refresh())

function statusLabel(status) {
    return STATUS_LABELS[Number(status)] || '未知'
}

async function hide(item) {
    if (pendingId.value) {
        return
    }
    pendingId.value = item.commentId
    try {
        await hideAdminComment(item.commentId)
        toast.success('已隐藏')
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '隐藏失败')
    } finally {
        pendingId.value = 0
    }
}

function askDelete(item) {
    deleteTarget.value = item
    deleteOpen.value = true
}

async function confirmDelete() {
    if (!deleteTarget.value || deleteLoading.value) {
        return
    }
    deleteLoading.value = true
    try {
        await deleteAdminComment(deleteTarget.value.commentId)
        toast.success('已删除')
        deleteOpen.value = false
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '删除失败')
    } finally {
        deleteLoading.value = false
    }
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="评论治理" back-to="/me"/>

        <main class="app-main comment-admin-page">
            <div class="filter-strip" aria-label="评论状态筛选">
                <button
                    v-for="item in filters"
                    :key="String(item.value)"
                    class="chip"
                    :class="{ 'is-active': statusFilter === item.value }"
                    type="button"
                    @click="statusFilter = item.value"
                >
                    {{ item.label }}
                </button>
            </div>

            <div v-if="loading" class="comment-list">
                <SkeletonBlock v-for="index in 4" :key="index" height="104px" radius="14px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <EmptyState
                v-else-if="!items.length"
                title="当前没有评论"
                description="切换状态筛选查看其他评论。"
            />

            <template v-else>
                <div class="comment-list">
                    <article v-for="item in items" :key="item.commentId" class="comment-card">
                        <div class="comment-head">
                            <span class="comment-user">用户 {{ item.userId }}</span>
                            <span class="status-chip" :class="`status-${item.status}`">
                                {{ statusLabel(item.status) }}
                            </span>
                        </div>
                        <p class="comment-content">{{ item.content }}</p>
                        <div class="comment-meta">
                            <span>短剧 {{ item.dramaId }}</span>
                            <span v-if="item.episodeId">剧集 {{ item.episodeId }}</span>
                            <span>{{ formatDateTime(item.createTime) }}</span>
                        </div>
                        <div class="comment-actions">
                            <button
                                v-if="Number(item.status) === 0 || Number(item.status) === 1"
                                class="btn btn-ghost btn-small"
                                type="button"
                                :disabled="pendingId === item.commentId"
                                @click="hide(item)"
                            >
                                {{ pendingId === item.commentId ? '处理中…' : '隐藏' }}
                            </button>
                            <button
                                v-if="Number(item.status) !== 3"
                                class="btn btn-danger btn-small"
                                type="button"
                                @click="askDelete(item)"
                            >
                                删除
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

        <ConfirmDialog
            v-model="deleteOpen"
            title="删除评论"
            :message="deleteTarget ? `确认删除这条评论？${deleteTarget.content.slice(0, 40)}` : ''"
            confirm-text="删除"
            danger
            :loading="deleteLoading"
            @confirm="confirmDelete"
        />
    </div>
</template>

<style scoped>
.comment-admin-page {
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

.comment-list {
    display: grid;
    gap: 10px;
}

.comment-card {
    display: grid;
    gap: 8px;
    padding: 14px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
}

.comment-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
}

.comment-user {
    color: var(--muted);
    font-size: 11px;
}

.status-chip {
    padding: 3px 9px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--muted);
    font-size: 10px;
}

.status-0 {
    color: var(--brand-2);
}

.status-1 {
    border-color: rgba(71, 209, 140, 0.4);
    color: var(--ok);
}

.status-2 {
    border-color: rgba(255, 179, 63, 0.4);
    color: var(--brand-2);
}

.status-3 {
    border-color: rgba(255, 92, 61, 0.4);
    color: var(--danger);
}

.comment-content {
    margin: 0;
    color: var(--text);
    font-size: 13px;
    line-height: 1.65;
    overflow-wrap: anywhere;
}

.comment-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    color: rgba(255, 255, 255, 0.34);
    font-size: 10px;
}

.comment-actions {
    display: flex;
    gap: 8px;
}

/* ---------- 桌面端（≥1024）：一行一条评论 ---------- */

@media (min-width: 1024px) {
    .comment-admin-page {
        gap: 14px;
    }

    .filter-strip {
        flex-wrap: wrap;
        overflow: visible;
    }

    .comment-list {
        gap: 10px;
    }

    .comment-card {
        grid-template-columns: minmax(240px, 280px) minmax(0, 1fr) auto;
        align-items: center;
        column-gap: 24px;
        row-gap: 6px;
        padding: 14px 20px;
    }

    .comment-head {
        grid-column: 1;
        grid-row: 1;
    }

    .comment-content {
        display: -webkit-box;
        grid-column: 2;
        grid-row: 1 / span 2;
        overflow: hidden;
        -webkit-box-orient: vertical;
        -webkit-line-clamp: 2;
    }

    .comment-meta {
        grid-column: 1;
        grid-row: 2;
        flex-wrap: nowrap;
        white-space: nowrap;
    }

    .comment-actions {
        grid-column: 3;
        grid-row: 1 / span 2;
        justify-content: flex-end;
    }
}
</style>
