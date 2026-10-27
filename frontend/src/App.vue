<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppTabBar from './components/AppTabBar.vue'
import ToastHost from './components/ToastHost.vue'
import DesktopSidebar from './components/layout/DesktopSidebar.vue'
import DesktopTopbar from './components/layout/DesktopTopbar.vue'
import { useMediaQuery } from './composables/useMediaQuery'

const route = useRoute()

const isDesktop = useMediaQuery('(min-width: 1024px)')
const activeTab = computed(() => route.meta.tab || '')
const immersive = computed(() => Boolean(route.meta.immersive))
const isPublic = computed(() => Boolean(route.meta.public))

// 1. 桌面壳只用于需要登录的普通页面；登录页和沉浸式播放保持独立布局
const showChrome = computed(() => isDesktop.value && !isPublic.value && !immersive.value)

// 2. 底部 TabBar 只在移动端出现，桌面端导航改由侧栏承担
const showTab = computed(() => Boolean(activeTab.value) && !isDesktop.value)
</script>

<template>
    <div class="app-root" :class="{ 'has-chrome': showChrome }">
        <DesktopSidebar v-if="showChrome"/>

        <div class="app-frame" :class="{ 'has-tab': showTab, 'is-public': isPublic }">
            <DesktopTopbar v-if="showChrome"/>
            <RouterView :key="route.fullPath"/>
            <AppTabBar v-if="showTab" :active="activeTab"/>
        </div>

        <ToastHost :has-tab="showTab" :immersive="immersive"/>
    </div>
</template>

<style scoped>
.app-root {
    min-height: 100dvh;
}

.app-root.has-chrome {
    display: grid;
    grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
}

/* 桌面端取消移动壳的窄屏约束，内容宽度交给 .app-main 控制 */
.app-root.has-chrome .app-frame {
    width: 100%;
    max-width: none;
    margin: 0;
    border: 0;
    box-shadow: none;
    background: transparent;
}
</style>
