<script setup>
const props = defineProps({
    episodes: {
        type: Array,
        default: () => []
    },
    currentId: {
        type: Number,
        default: 0
    },
    unlockedIds: {
        type: Set,
        default: () => new Set()
    }
})

defineEmits(['select'])

/**
 * 剧集角标：免费 / 已解锁 / 积分解锁。
 */
function badge(episode) {
    if (episode.free) {
        return '免费'
    }
    if (props.unlockedIds.has(episode.episodeId)) {
        return '已解锁'
    }
    return `${episode.pricePoints} 积分`
}
</script>

<template>
    <div v-if="episodes.length" class="episode-rail" aria-label="选集">
        <button
            v-for="episode in episodes"
            :key="episode.episodeId"
            class="rail-item"
            :class="{
                'is-active': episode.episodeId === currentId,
                'is-locked': !episode.free && !unlockedIds.has(episode.episodeId)
            }"
            type="button"
            @click="$emit('select', episode.episodeId)"
        >
            <span class="rail-no">第{{ episode.episodeNo }}集</span>
            <span class="rail-name">{{ episode.title }}</span>
            <span class="rail-badge">{{ badge(episode) }}</span>
        </button>
    </div>
</template>

<style scoped>
.episode-rail {
    display: flex;
    gap: 8px;
    margin: 0 -16px;
    padding: 0 16px 2px;
    overflow-x: auto;
    scrollbar-width: none;
}

.episode-rail::-webkit-scrollbar {
    display: none;
}

.rail-item {
    display: grid;
    flex: none;
    gap: 3px;
    width: 94px;
    padding: 8px 9px;
    border: 1px solid var(--line);
    border-radius: 11px;
    background: rgba(255, 255, 255, 0.035);
    color: var(--text);
    text-align: left;
}

.rail-item.is-active {
    border-color: rgba(255, 179, 63, 0.65);
    background: var(--brand-soft);
}

.rail-item.is-locked {
    color: var(--muted);
}

.rail-no {
    color: var(--brand-2);
    font-size: 9px;
    font-weight: 800;
    letter-spacing: 0.06em;
}

.rail-name {
    overflow: hidden;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.rail-badge {
    color: var(--muted);
    font-size: 9px;
}

.rail-item.is-active .rail-badge {
    color: var(--brand-2);
}
</style>
