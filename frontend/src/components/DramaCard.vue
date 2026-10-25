<script setup>
import GradientCover from './GradientCover.vue'
import { formatCount } from '../utils/format'

defineProps({
    drama: {
        type: Object,
        required: true
    }
})
</script>

<template>
    <RouterLink class="drama-card" :to="`/drama/${drama.dramaId}`">
        <GradientCover :seed="drama.dramaId" :title="drama.title" ratio="poster">
            <span v-if="drama.categoryName" class="card-chip">{{ drama.categoryName }}</span>
            <span class="card-episodes">{{ drama.episodeCount }} 集</span>
        </GradientCover>
        <div class="card-copy">
            <h3>{{ drama.title || '未命名短剧' }}</h3>
            <p>{{ formatCount(drama.viewCount) }} 播放 · {{ formatCount(drama.likeCount) }} 赞</p>
        </div>
    </RouterLink>
</template>

<style scoped>
.drama-card {
    display: flex;
    flex: none;
    flex-direction: column;
    gap: 8px;
    width: 136px;
    color: var(--text);
}

.card-chip,
.card-episodes {
    position: absolute;
    z-index: 1;
    padding: 3px 7px;
    border-radius: 7px;
    background: rgba(0, 0, 0, 0.42);
    color: rgba(255, 255, 255, 0.9);
    font-size: 10px;
    line-height: 1.4;
    backdrop-filter: blur(6px);
}

.card-chip {
    top: 8px;
    left: 8px;
}

.card-episodes {
    right: 8px;
    bottom: 8px;
}

.card-copy {
    display: grid;
    gap: 3px;
}

.card-copy h3 {
    display: -webkit-box;
    overflow: hidden;
    font-size: 13px;
    font-weight: 700;
    line-height: 1.4;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.card-copy p {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}
</style>
