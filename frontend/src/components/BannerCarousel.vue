<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import GradientCover from './GradientCover.vue'

const props = defineProps({
    banners: {
        type: Array,
        default: () => []
    }
})

const current = ref(0)
let timer = null
let touchStartX = 0
let swiped = false

const normalized = computed(() => props.banners.filter((item) => item?.dramaId))
const banner = computed(() => normalized.value[current.value] || null)
const canAuto = computed(() => normalized.value.length > 1)

/**
 * 轮播间隔；系统开启减少动画时不再自动播放。
 */
function startAuto() {
    stopAuto()
    if (!canAuto.value) {
        return
    }
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches) {
        return
    }
    timer = window.setInterval(() => {
        current.value = (current.value + 1) % normalized.value.length
    }, 4000)
}

function stopAuto() {
    if (timer) {
        window.clearInterval(timer)
        timer = null
    }
}

function go(index) {
    if (!normalized.value.length) {
        return
    }
    current.value = (index + normalized.value.length) % normalized.value.length
    startAuto()
}

/**
 * 移动端左右滑动切换。
 */
function onTouchStart(event) {
    touchStartX = event.touches[0]?.clientX || 0
    swiped = false
    stopAuto()
}

function onTouchEnd(event) {
    const delta = (event.changedTouches[0]?.clientX || 0) - touchStartX
    if (Math.abs(delta) > 40) {
        swiped = true
        go(current.value + (delta < 0 ? 1 : -1))
        window.setTimeout(() => {
            swiped = false
        }, 320)
    } else {
        startAuto()
    }
}

/**
 * 滑动结束后浏览器仍会补发 click，这里拦掉误触跳转。
 */
function onClickCapture(event) {
    if (swiped) {
        event.preventDefault()
        event.stopPropagation()
    }
}

watch(() => normalized.value.length, () => {
    current.value = 0
    startAuto()
})

onMounted(startAuto)
onBeforeUnmount(stopAuto)
</script>

<template>
    <section
        v-if="banner"
        class="banner"
        @touchstart.passive="onTouchStart"
        @touchend.passive="onTouchEnd"
        @click.capture="onClickCapture"
        @pointerenter="stopAuto"
        @pointerleave="startAuto"
    >
        <Transition name="banner-fade" mode="out-in">
            <RouterLink
                :key="banner.dramaId"
                class="banner-slide"
                :to="`/drama/${banner.dramaId}`"
            >
                <GradientCover :seed="banner.dramaId" :title="banner.title" ratio="wide" :rounded="false">
                    <span class="banner-scrim" aria-hidden="true"></span>
                    <span class="banner-copy">
                        <span class="banner-eyebrow">正在热播</span>
                        <strong>{{ banner.title }}</strong>
                        <span class="banner-subtitle">{{ banner.subtitle }}</span>
                    </span>
                </GradientCover>
            </RouterLink>
        </Transition>

        <div v-if="normalized.length > 1" class="banner-dots">
            <button
                v-for="(item, index) in normalized"
                :key="item.dramaId"
                class="banner-dot"
                :class="{ 'is-active': index === current }"
                type="button"
                :aria-label="`第 ${index + 1} 张推荐`"
                @click="go(index)"
            ></button>
        </div>
    </section>
</template>

<style scoped>
.banner {
    position: relative;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 20px;
    box-shadow: var(--shadow);
}

.banner-slide {
    display: block;
    color: #fff;
}

.banner-scrim {
    position: absolute;
    inset: 0;
    background:
        linear-gradient(180deg, rgba(0, 0, 0, 0.05) 30%, rgba(0, 0, 0, 0.78) 100%),
        linear-gradient(90deg, rgba(0, 0, 0, 0.32), transparent 62%);
}

.banner-copy {
    position: absolute;
    right: 16px;
    bottom: 16px;
    left: 16px;
    display: grid;
    gap: 4px;
    z-index: 1;
}

.banner-eyebrow {
    color: var(--brand-2);
    font-size: 10px;
    font-weight: 800;
    letter-spacing: 0.16em;
}

.banner-copy strong {
    overflow: hidden;
    font-size: 20px;
    line-height: 1.25;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.banner-subtitle {
    overflow: hidden;
    color: rgba(255, 255, 255, 0.72);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.banner-dots {
    position: absolute;
    right: 13px;
    bottom: 11px;
    display: flex;
    gap: 5px;
}

.banner-dot {
    width: 6px;
    height: 6px;
    padding: 0;
    border: none;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.35);
    transition: width 160ms ease, background 160ms ease;
}

.banner-dot.is-active {
    width: 16px;
    background: var(--brand-2);
}

.banner-fade-enter-active,
.banner-fade-leave-active {
    transition: opacity 220ms ease;
}

.banner-fade-enter-from,
.banner-fade-leave-to {
    opacity: 0;
}
</style>
