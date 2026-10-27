<script setup>
import AppModal from '../AppModal.vue'
import CompanionChat from './CompanionChat.vue'

/**
 * 移动端 AI 伴看弹层：内容全部由 CompanionChat 承担。
 */
defineProps({
    modelValue: {
        type: Boolean,
        default: false
    },
    dramaId: {
        type: [String, Number],
        required: true
    },
    dramaTitle: {
        type: String,
        default: ''
    },
    episode: {
        type: Object,
        default: null
    }
})

const emit = defineEmits(['update:modelValue'])
</script>

<template>
    <AppModal
        :model-value="modelValue"
        title="AI 伴看"
        @update:model-value="emit('update:modelValue', $event)"
    >
        <CompanionChat
            :drama-id="dramaId"
            :drama-title="dramaTitle"
            :episode="episode"
        />
    </AppModal>
</template>
