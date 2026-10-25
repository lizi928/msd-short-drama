<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { chatWithCompanion } from '../../api/companion'
import { useToastStore } from '../../stores/toast'
import AppModal from '../AppModal.vue'
import CommentComposer from '../comments/CommentComposer.vue'

const SESSION_PATTERN = /^[A-Za-z0-9_-]{1,64}$/
const MAX_MESSAGES = 50
const SUGGESTIONS = ['这一集讲了什么', '帮我理一下人物关系', '前情回顾']

const props = defineProps({
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
const toast = useToastStore()

const messages = ref([])
const sessionId = ref('')
const sending = ref(false)
const error = ref('')
const listEl = ref(null)
const composerRef = ref(null)

let sequence = 0

const open = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
})

const episodeLabel = computed(() => {
    if (!props.episode) {
        return '当前集'
    }
    const title = String(props.episode.title || '').trim()
    if (title.startsWith('第')) {
        return title
    }
    return title
        ? `第${props.episode.episodeNo}集 · ${title}`
        : `第${props.episode.episodeNo}集`
})

const storageKey = computed(() => (
    props.dramaId ? `msd.companion.${props.dramaId}` : 'msd.companion.default'
))

/**
 * 生成符合后端 sessionId 正则的会话 ID。
 */
function newSessionId() {
    if (globalThis.crypto?.randomUUID) {
        return globalThis.crypto.randomUUID()
    }
    return `d${props.dramaId}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * 生成消息 ID，仅用于本地列表 key。
 */
function nextId() {
    sequence += 1
    return `${Date.now()}-${sequence}`
}

/**
 * 校验并归一本地消息，避免旧数据破坏渲染。
 */
function normalizeMessages(value) {
    if (!Array.isArray(value)) {
        return []
    }
    return value
        .filter((item) => item
            && (item.role === 'user' || item.role === 'assistant')
            && typeof item.text === 'string')
        .slice(-MAX_MESSAGES)
        .map((item) => ({
            id: item.id || nextId(),
            role: item.role,
            text: item.text,
            degraded: Boolean(item.degraded),
            degradedFlags: Array.isArray(item.degradedFlags)
                ? item.degradedFlags.filter((flag) => typeof flag === 'string')
                : []
        }))
}

/**
 * 从 sessionStorage 恢复会话；不存在或损坏时创建新会话。
 */
function restore() {
    messages.value = []
    sessionId.value = newSessionId()
    sending.value = false
    error.value = ''
    try {
        const raw = sessionStorage.getItem(storageKey.value)
        if (!raw) {
            return
        }
        const stored = JSON.parse(raw)
        if (stored && typeof stored.sessionId === 'string'
            && SESSION_PATTERN.test(stored.sessionId)) {
            sessionId.value = stored.sessionId
        }
        messages.value = normalizeMessages(stored?.messages)
    } catch {
        // 本地存储损坏时直接使用新会话
    }
}

/**
 * 保存会话 ID 和最近消息，存储失败不影响聊天。
 */
function persist() {
    try {
        sessionStorage.setItem(storageKey.value, JSON.stringify({
            sessionId: sessionId.value,
            messages: messages.value.slice(-MAX_MESSAGES)
        }))
    } catch {
        // 忽略存储配额或隐私模式异常
    }
}

/**
 * 滚动到消息列表底部。
 */
async function scrollToBottom() {
    await nextTick()
    if (listEl.value) {
        listEl.value.scrollTop = listEl.value.scrollHeight
    }
}

/**
 * 降级标记转中文提示。
 */
function flagLabel(flag) {
    const labels = {
        drama: '短剧资料不可用',
        summary: '本集摘要缺失',
        context: '前情检索降级',
        vector: '向量检索降级',
        bm25: '关键词检索降级',
        episode_index: '分集索引未建立',
        generation: '回复生成降级',
        memory_summary: '会话记忆降级',
        timeout: '响应超时',
        not_found: '剧集不可见'
    }
    return labels[flag] || flag
}

/**
 * 发送一条消息，失败时恢复输入内容。
 */
async function sendText(text) {
    const content = String(text || '').trim()
    if (!content || sending.value || !props.episode) {
        return
    }
    const episodeId = Number(props.episode.episodeId)
    if (!Number.isFinite(episodeId) || episodeId <= 0) {
        return
    }

    composerRef.value?.clear()
    error.value = ''
    const userMessage = {
        id: nextId(),
        role: 'user',
        text: content,
        degraded: false,
        degradedFlags: []
    }
    messages.value.push(userMessage)
    persist()
    scrollToBottom()
    sending.value = true

    try {
        const data = await chatWithCompanion({
            message: content,
            sessionId: sessionId.value,
            episodeId
        })
        if (data?.sessionId && SESSION_PATTERN.test(data.sessionId)) {
            sessionId.value = data.sessionId
        }
        messages.value.push({
            id: nextId(),
            role: 'assistant',
            text: data?.reply || '暂时没有回复，请稍后再试。',
            degraded: Boolean(data?.degraded),
            degradedFlags: Array.isArray(data?.degradedFlags) ? data.degradedFlags : []
        })
    } catch (cause) {
        messages.value = messages.value.filter((item) => item.id !== userMessage.id)
        composerRef.value?.set(content)
        error.value = cause?.message || '发送失败，请稍后再试'
        toast.push(error.value)
    } finally {
        sending.value = false
        persist()
        scrollToBottom()
    }
}

/**
 * 清空消息并开始新会话。
 */
function reset() {
    messages.value = []
    sessionId.value = newSessionId()
    error.value = ''
    persist()
    composerRef.value?.clear()
    toast.success('已开始新会话')
}

watch(() => props.dramaId, restore, { immediate: true })

watch(() => props.modelValue, (value) => {
    if (value) {
        scrollToBottom()
    }
})

watch(() => messages.value.length, () => {
    scrollToBottom()
})
</script>

<template>
    <AppModal v-model="open" title="AI 伴看">
        <div class="companion">
            <div class="companion-meta">
                <span class="episode-chip">{{ episodeLabel }}</span>
                <button v-if="messages.length" class="reset-btn" type="button" @click="reset">
                    新会话
                </button>
            </div>
            <p class="companion-hint">
                {{ dramaTitle || '当前短剧' }} · 只讨论当前集及之前 · AI 可能出错，请以正片为准
            </p>

            <div ref="listEl" class="message-list" aria-live="polite">
                <div v-if="!messages.length" class="empty">
                    <p>这一集有什么想聊的？</p>
                    <div class="suggestions">
                        <button
                            v-for="item in SUGGESTIONS"
                            :key="item"
                            class="suggestion"
                            type="button"
                            @click="sendText(item)"
                        >
                            {{ item }}
                        </button>
                    </div>
                </div>

                <div
                    v-for="message in messages"
                    :key="message.id"
                    class="msg"
                    :class="message.role"
                >
                    <div class="bubble">{{ message.text }}</div>
                    <div v-if="message.degraded && !message.degradedFlags.length" class="flag-row">
                        <span class="flag">资料可能不完整</span>
                    </div>
                    <div v-else-if="message.degradedFlags.length" class="flag-row">
                        <span v-for="flag in message.degradedFlags" :key="flag" class="flag">
                            {{ flagLabel(flag) }}
                        </span>
                    </div>
                </div>

                <div v-if="sending" class="msg assistant">
                    <div class="bubble typing">正在回忆剧情…</div>
                </div>

                <p v-if="error" class="error">{{ error }}</p>
            </div>
        </div>

        <template #footer>
            <CommentComposer
                ref="composerRef"
                placeholder="问问这一集的剧情…"
                :submitting="sending"
                submit-on-enter
                @submit="sendText"
            />
        </template>
    </AppModal>
</template>

<style scoped>
.companion {
    display: grid;
    gap: 8px;
}

.companion-meta {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
}

.episode-chip {
    overflow: hidden;
    padding: 3px 9px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--brand-2);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.reset-btn {
    flex: none;
    padding: 0;
    border: none;
    background: none;
    color: var(--muted);
    font-size: 11px;
}

.reset-btn:hover {
    color: var(--text);
}

.companion-hint {
    margin: 0;
    color: rgba(255, 255, 255, 0.42);
    font-size: 10.5px;
    line-height: 1.5;
}

.message-list {
    display: grid;
    gap: 10px;
    max-height: min(48dvh, 420px);
    overflow-y: auto;
    padding: 4px 3px 4px 0;
    overscroll-behavior: contain;
    scrollbar-width: thin;
}

.empty {
    display: grid;
    gap: 10px;
    padding: 18px 4px;
    color: var(--muted);
    font-size: 12px;
}

.empty p {
    margin: 0;
}

.suggestions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

.suggestion {
    padding: 6px 11px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.04);
    color: var(--text);
    font-size: 11px;
}

.suggestion:hover {
    border-color: rgba(255, 179, 63, 0.5);
    color: var(--brand-2);
}

.msg {
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: flex-start;
}

.msg.user {
    align-items: flex-end;
}

.bubble {
    max-width: 88%;
    padding: 9px 12px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.055);
    color: var(--text);
    font-size: 13px;
    line-height: 1.65;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
}

.msg.user .bubble {
    border: none;
    border-radius: 14px 14px 4px 14px;
    background: linear-gradient(135deg, var(--brand), var(--brand-2));
    color: #1a0d05;
}

.msg.assistant .bubble {
    border-bottom-left-radius: 4px;
}

.typing {
    color: var(--muted);
    font-style: italic;
}

.flag-row {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
}

.flag {
    padding: 2px 7px;
    border-radius: 999px;
    background: rgba(255, 179, 63, 0.12);
    color: var(--brand-2);
    font-size: 10px;
}

.error {
    margin: 0;
    color: var(--danger);
    font-size: 11px;
}

:deep(.modal-foot) {
    grid-template-columns: minmax(0, 1fr);
}

:deep(.composer) {
    padding: 0;
    border: none;
    background: transparent;
}

:deep(.composer-input) {
    min-height: 54px;
}

@media (max-width: 420px) {
    .message-list {
        max-height: 42dvh;
    }
}
</style>
