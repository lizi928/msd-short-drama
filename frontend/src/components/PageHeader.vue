<script setup>
import { useRouter } from 'vue-router'

const props = defineProps({
    title: {
        type: String,
        default: ''
    },
    subtitle: {
        type: String,
        default: ''
    },
    showBack: {
        type: Boolean,
        default: true
    },
    backTo: {
        type: String,
        default: '/'
    }
})

const router = useRouter()

/**
 * 有站内历史时返回上一页，直接打开深链时回退到指定页面。
 */
function goBack() {
    if (window.history.state?.back) {
        router.back()
        return
    }
    router.replace(props.backTo)
}
</script>

<template>
    <header class="page-header">
        <button v-if="showBack" class="icon-btn" type="button" aria-label="返回" @click="goBack">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M15 5l-7 7 7 7"/>
            </svg>
        </button>
        <div class="page-header-copy">
            <h1>{{ title }}</h1>
            <span v-if="subtitle">{{ subtitle }}</span>
        </div>
        <div class="page-header-right">
            <slot name="right"/>
        </div>
    </header>
</template>

<style scoped>
.page-header {
    position: sticky;
    top: 0;
    z-index: 30;
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 56px;
    padding: calc(env(safe-area-inset-top) + 8px) 14px 8px;
    border-bottom: 1px solid var(--line);
    background: rgba(9, 11, 16, 0.86);
    backdrop-filter: blur(18px);
}

.icon-btn {
    display: inline-grid;
    flex: none;
    place-items: center;
    width: 36px;
    height: 36px;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.04);
    color: var(--text);
}

.icon-btn svg {
    width: 18px;
    height: 18px;
}

.page-header-copy {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 1px;
}

.page-header-copy h1 {
    overflow: hidden;
    font-size: 16px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.page-header-copy span {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.page-header-right {
    display: flex;
    flex: none;
    align-items: center;
    gap: 8px;
}
</style>
