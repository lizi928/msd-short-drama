<script setup>
import { computed, ref } from 'vue'
import { createComment, deleteComment, fetchComments } from '../../api/interaction'
import { useInteractionAck } from '../../composables/useInteractionAck'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toast'
import { usePagedList } from '../../composables/usePagedList'
import CommentComposer from './CommentComposer.vue'
import CommentThread from './CommentThread.vue'
import ConfirmDialog from '../ConfirmDialog.vue'
import EmptyState from '../EmptyState.vue'
import ErrorState from '../ErrorState.vue'
import LoadMore from '../LoadMore.vue'
import SkeletonBlock from '../SkeletonBlock.vue'

const props = defineProps({
    dramaId: {
        type: [String, Number],
        required: true
    },
    episodeId: {
        type: [String, Number],
        default: null
    },
    title: {
        type: String,
        default: '评论'
    },
    showTitle: {
        type: Boolean,
        default: true
    }
})

const auth = useAuthStore()
const toast = useToastStore()

const {
    items,
    total,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    initialized,
    refresh,
    loadMore,
    prepend,
    remove
} = usePagedList(
    ({ pageNo, pageSize }) => fetchComments({
        dramaId: Number(props.dramaId),
        episodeId: props.episodeId === null ? undefined : Number(props.episodeId),
        pageNo,
        pageSize
    }),
    {
        pageSize: 10,
        keyOf: (item) => item.commentId
    }
)

const composerRef = ref(null)
const submitting = ref(false)
const pendingRequestId = ref('')
const deleteTarget = ref(null)
const deleting = ref(false)
const ack = useInteractionAck()

const confirmVisible = computed({
    get: () => Boolean(deleteTarget.value),
    set: (value) => {
        if (!value) {
            deleteTarget.value = null
        }
    }
})

const deleteMessage = computed(() => (
    Number(deleteTarget.value?.replyCount) > 0
        ? '这条评论下的回复也会一起删除，且无法恢复。'
        : '删除后无法恢复，确定删除这条评论？'
))

/**
 * 生成评论请求幂等键；重试时复用，避免重复评论。
 */
function newRequestId() {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID()
    }
    return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * 发布根评论后本地插入，避免整页刷新。
 */
async function submitRoot(content) {
    if (submitting.value) {
        return
    }
    submitting.value = true
    if (!pendingRequestId.value) {
        pendingRequestId.value = newRequestId()
    }
    try {
        const result = await createComment({
            dramaId: Number(props.dramaId),
            episodeId: props.episodeId === null ? undefined : Number(props.episodeId),
            content,
            clientRequestId: pendingRequestId.value
        })
        pendingRequestId.value = ''
        prepend({
            commentId: result.commentId,
            dramaId: Number(props.dramaId),
            episodeId: props.episodeId === null ? null : Number(props.episodeId),
            userId: auth.user?.id ?? 0,
            nickname: auth.displayName,
            avatarUrl: auth.user?.avatarUrl || '',
            rootId: 0,
            parentId: 0,
            replyToUserId: null,
            replyToNickname: null,
            content,
            likeCount: 0,
            replyCount: 0,
            liked: false,
            createTime: new Date().toISOString()
        })
        if (result.eventId) {
            ack.track(result.eventId, {
                onDead: () => {
                    remove(result.commentId)
                    toast.push('评论同步失败，已移除本地评论')
                }
            })
        }
        composerRef.value?.clear()
        toast.success('评论已发布')
    } catch (cause) {
        toast.push(cause?.message || '评论失败')
    } finally {
        submitting.value = false
    }
}

function askDelete(comment) {
    deleteTarget.value = comment
}

/**
 * 删除根评论，后端会级联删除回复。
 */
async function confirmDelete() {
    if (!deleteTarget.value) {
        return
    }
    deleting.value = true
    try {
        await deleteComment(deleteTarget.value.commentId)
        remove(deleteTarget.value.commentId)
        toast.success('评论已删除')
        deleteTarget.value = null
    } catch (cause) {
        toast.push(cause?.message || '删除失败')
    } finally {
        deleting.value = false
    }
}
</script>

<template>
    <section class="comment-section">
        <div v-if="showTitle" class="section-title">
            <h2>{{ title }}</h2>
            <span>{{ initialized ? `${total} 条` : '' }}</span>
        </div>

        <CommentComposer
            ref="composerRef"
            placeholder="说点什么…"
            :submitting="submitting"
            @submit="submitRoot"
        />

        <div v-if="loading" class="comment-loading">
            <SkeletonBlock v-for="index in 3" :key="index" height="58px" radius="12px"/>
        </div>

        <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

        <template v-else>
            <EmptyState
                v-if="!items.length"
                title="还没有评论"
                description="来发第一条评论吧。"
            />
            <div v-else class="comment-list">
                <CommentThread
                    v-for="comment in items"
                    :key="comment.commentId"
                    :root="comment"
                    :drama-id="dramaId"
                    :episode-id="episodeId"
                    @delete="askDelete"
                />
            </div>

            <LoadMore
                v-if="items.length"
                :loading="loadingMore"
                :has-more="hasMore"
                :error="loadMoreError"
                :total="total"
                @load="loadMore"
            />
        </template>

        <ConfirmDialog
            v-model="confirmVisible"
            title="删除评论"
            :message="deleteMessage"
            confirm-text="删除"
            danger
            :loading="deleting"
            @confirm="confirmDelete"
        />
    </section>
</template>

<style scoped>
.comment-section {
    display: grid;
    gap: 12px;
    padding-top: 6px;
}

.comment-loading {
    display: grid;
    gap: 10px;
}

.comment-list {
    display: grid;
}
</style>
