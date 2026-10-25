<script setup>
defineProps({
    width: {
        type: String,
        default: '100%'
    },
    height: {
        type: String,
        default: '16px'
    },
    radius: {
        type: String,
        default: '10px'
    }
})
</script>

<template>
    <span class="skeleton" :style="{ width, height, borderRadius: radius }" aria-hidden="true"></span>
</template>

<style scoped>
.skeleton {
    display: block;
    background: linear-gradient(
        100deg,
        rgba(255, 255, 255, 0.05) 20%,
        rgba(255, 255, 255, 0.11) 38%,
        rgba(255, 255, 255, 0.05) 56%
    );
    background-size: 220% 100%;
    animation: skeleton-shimmer 1.4s ease infinite;
}

@keyframes skeleton-shimmer {
    from {
        background-position: 130% 0;
    }
    to {
        background-position: -30% 0;
    }
}

@media (prefers-reduced-motion: reduce) {
    .skeleton {
        animation: none;
    }
}
</style>
