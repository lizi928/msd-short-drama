<script setup>
import { computed } from 'vue'
import CommentSection from '../comments/CommentSection.vue'
import CompanionChat from '../companion/CompanionChat.vue'
import EpisodeRail from '../player/EpisodeRail.vue'

const props = defineProps({
    detail: {
        type: Object,
        required: true
    },
    episodes: {
        type: Array,
        default: () => []
    },
    activeEpisode: {
        type: Object,
        default: null
    },
    unlockedIds: {
        type: Set,
        default: () => new Set()
    },
    tab: {
        type: String,
        default: 'episodes'
    },
    commentsScope: {
        type: String,
        default: 'episode'
    }
})

const emit = defineEmits(['update:tab', 'update:commentsScope', 'select'])

const tabs = [
    { key: 'episodes', label: '选集' },
    { key: 'comments', label: '评论' },
    { key: 'companion', label: 'AI 伴看' }
]

// 1. 当前集免费或已解锁才允许伴看
const activeUnlocked = computed(() => {
    const episode = props.activeEpisode
    if (!episode) {
        return false
    }
    return Boolean(episode.free) || props.unlockedIds.has(episode.episodeId)
})

// 2. 切集时“本集”评论需要重新加载，“全剧”保持同一实例
const commentKey = computed(() => (
    props.commentsScope === 'episode'
        ? `episode-${props.activeEpisode?.episodeId || 0}`
        : 'drama'
))

/**
 * 切换评论范围。
 */
function changeScope(scope) {
    if (props.commentsScope === scope) {
        return
    }
    emit('update:commentsScope', scope)
}
</script>

<template>
    <aside class="feed-workspace" aria-label="剧集工作区">
        <div class="ws-tabs" role="tablist" aria-label="工作区视图">
            <button
                v-for="item in tabs"
                :key="item.key"
                class="ws-tab"
                :class="{ 'is-active': tab === item.key }"
                type="button"
                role="tab"
                :aria-selected="tab === item.key"
                @click="emit('update:tab', item.key)"
            >
                {{ item.label }}
            </button>
        </div>

        <div class="ws-body">
            <div v-if="tab === 'episodes'" class="ws-pane">
                <p class="ws-note">
                    共 {{ episodes.length }} 集 · 当前第 {{ activeEpisode?.episodeNo || 1 }} 集
                </p>
                <EpisodeRail
                    variant="panel"
                    :episodes="episodes"
                    :current-id="activeEpisode?.episodeId || 0"
                    :unlocked-ids="unlockedIds"
                    @select="emit('select', $event)"
                />
            </div>

            <div v-else-if="tab === 'comments'" class="ws-pane ws-pane-comments">
                <div class="comment-tabs">
                    <button
                        class="comment-tab"
                        :class="{ 'is-active': commentsScope === 'episode' }"
                        type="button"
                        @click="changeScope('episode')"
                    >
                        本集
                    </button>
                    <button
                        class="comment-tab"
                        :class="{ 'is-active': commentsScope === 'drama' }"
                        type="button"
                        @click="changeScope('drama')"
                    >
                        全剧
                    </button>
                </div>

                <div class="ws-scroll">
                    <CommentSection
                        v-if="activeEpisode"
                        :key="commentKey"
                        :drama-id="detail.id"
                        :episode-id="commentsScope === 'episode' ? activeEpisode.episodeId : null"
                        :show-title="false"
                    />
                </div>
            </div>

            <div v-else class="ws-pane ws-pane-companion">
                <CompanionChat
                    v-if="activeEpisode"
                    :drama-id="detail.id"
                    :drama-title="detail.title"
                    :episode="activeEpisode"
                    :disabled="!activeUnlocked"
                />
            </div>
        </div>
    </aside>
</template>

<style scoped>
.feed-workspace {
    display: flex;
    min-width: 0;
    height: 100dvh;
    flex-direction: column;
    border-left: 1px solid var(--line);
    background: #0b0e14;
}

.ws-tabs {
    display: flex;
    flex: none;
    gap: 4px;
    padding: 12px 14px 0;
    border-bottom: 1px solid var(--line);
}

.ws-tab {
    flex: 1;
    height: 40px;
    padding: 0;
    border: none;
    border-bottom: 2px solid transparent;
    background: none;
    color: var(--muted);
    font-size: 13px;
    font-weight: 700;
    transition: color 140ms ease, border-color 140ms ease;
}

.ws-tab:hover {
    color: var(--text);
}

.ws-tab.is-active {
    border-bottom-color: var(--brand-2);
    color: var(--brand-2);
}

.ws-body {
    min-height: 0;
    flex: 1;
}

.ws-pane {
    height: 100%;
    padding: 16px;
    overflow-y: auto;
    scrollbar-width: thin;
}

.ws-note {
    margin: 0 0 10px;
    color: rgba(255, 255, 255, 0.34);
    font-size: 11px;
}

/* ---------- 评论 ---------- */

.ws-pane-comments {
    display: flex;
    flex-direction: column;
    padding: 0;
    overflow: hidden;
}

.comment-tabs {
    display: flex;
    flex: none;
    gap: 8px;
    padding: 12px 16px;
    border-bottom: 1px solid var(--line);
}

.comment-tab {
    height: 30px;
    padding: 0 14px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: transparent;
    color: var(--muted);
    font-size: 12px;
}

.comment-tab.is-active {
    border-color: rgba(255, 179, 63, 0.55);
    background: var(--brand-soft);
    color: var(--brand-2);
}

.ws-scroll {
    min-height: 0;
    flex: 1;
    padding: 14px 16px 20px;
    overflow-y: auto;
    scrollbar-width: thin;
}

/* ---------- AI 伴看 ---------- */

.ws-pane-companion {
    display: flex;
    flex-direction: column;
    padding: 16px;
    overflow: hidden;
}

.ws-pane-companion :deep(.companion-chat) {
    min-height: 0;
    flex: 1;
}

/* 侧栏高度足够，消息列表不再限制固定高度 */
.ws-pane-companion :deep(.message-list) {
    max-height: none;
}
</style>
