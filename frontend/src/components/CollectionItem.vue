<script setup>
import GradientCover from './GradientCover.vue'
import { formatRelative } from '../utils/format'

defineProps({
    item: {
        type: Object,
        required: true
    },
    timeLabel: {
        type: String,
        default: '收藏于'
    },
    timeValue: {
        type: String,
        default: ''
    },
    actionText: {
        type: String,
        default: '取消'
    },
    actionLoading: {
        type: Boolean,
        default: false
    }
})

defineEmits(['open', 'action'])
</script>

<template>
    <article class="collection-item">
        <button
            class="item-main"
            type="button"
            :disabled="!item.title"
            @click="$emit('open', item)"
        >
            <span class="item-cover">
                <GradientCover :seed="item.dramaId" :title="item.title" ratio="thumb"/>
            </span>
            <span class="item-copy">
                <strong>{{ item.title || '内容暂不可见' }}</strong>
                <span v-if="item.subtitle" class="item-subtitle">{{ item.subtitle }}</span>
                <span class="item-meta">
                    {{ item.episodeCount ?? '—' }} 集 · {{ timeLabel }} {{ formatRelative(timeValue) }}
                </span>
            </span>
        </button>

        <button
            class="item-action"
            type="button"
            :disabled="actionLoading"
            @click="$emit('action', item)"
        >
            {{ actionLoading ? '处理中…' : actionText }}
        </button>
    </article>
</template>

<style scoped>
.collection-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.03);
}

.item-main {
    display: flex;
    min-width: 0;
    flex: 1;
    align-items: center;
    gap: 11px;
    padding: 0;
    border: none;
    background: none;
    color: inherit;
    text-align: left;
}

.item-main:disabled {
    opacity: 0.7;
}

.item-cover {
    flex: none;
    width: 72px;
    overflow: hidden;
    border-radius: 9px;
}

.item-copy {
    display: grid;
    min-width: 0;
    gap: 4px;
}

.item-copy strong {
    overflow: hidden;
    font-size: 13px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.item-subtitle,
.item-meta {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.item-action {
    flex: none;
    padding: 6px 9px;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: transparent;
    color: var(--brand-2);
    font-size: 11px;
    white-space: nowrap;
}

.item-action:disabled {
    opacity: 0.5;
}
</style>
