<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppTabBar from './components/AppTabBar.vue'
import ToastHost from './components/ToastHost.vue'

const route = useRoute()
const activeTab = computed(() => route.meta.tab || '')
const immersive = computed(() => Boolean(route.meta.immersive))
</script>

<template>
    <div class="app-root">
        <div class="app-frame" :class="{ 'has-tab': activeTab }">
            <RouterView :key="route.fullPath"/>
            <AppTabBar v-if="activeTab" :active="activeTab"/>
        </div>
        <ToastHost :has-tab="Boolean(activeTab)" :immersive="immersive"/>
    </div>
</template>

<style scoped>
.app-root {
    min-height: 100dvh;
}
</style>
