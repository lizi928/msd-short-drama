<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createDrama, fetchMyDrama, submitDrama, updateDrama } from '../api/creator'
import { fetchHome } from '../api/drama'
import { useToastStore } from '../stores/toast'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const route = useRoute()
const router = useRouter()
const toast = useToastStore()

const dramaId = computed(() => (route.params.id ? Number(route.params.id) : 0))
const isNew = computed(() => !dramaId.value)

const form = reactive({
    title: '',
    subtitle: '',
    coverUrl: '',
    description: '',
    categoryId: 0,
    actors: ''
})

const episodes = ref([emptyEpisode(1)])
const categories = ref([])
const reviewStatus = ref(0)
const loading = ref(!isNew.value)
const saving = ref(false)
const error = ref('')

const canSave = computed(() => isNew.value || reviewStatus.value === 0 || reviewStatus.value === 3)

function emptyEpisode(episodeNo) {
    return {
        episodeNo,
        title: `第${episodeNo}集`,
        durationSeconds: 120,
        pricePoints: 0,
        status: 1
    }
}

/**
 * 加载可见分类，供新建/编辑时选择。
 */
async function loadCategories() {
    try {
        const home = await fetchHome()
        categories.value = home?.categories || []
        if (!form.categoryId && categories.value.length) {
            form.categoryId = categories.value[0].id
        }
    } catch {
        categories.value = []
    }
}

/**
 * 编辑页回填短剧和剧集；新建页只加载分类。
 */
async function load() {
    if (isNew.value) {
        await loadCategories()
        return
    }
    loading.value = true
    error.value = ''
    try {
        const [detail] = await Promise.all([fetchMyDrama(dramaId.value), loadCategories()])
        const drama = detail?.drama
        if (!drama) {
            error.value = '短剧不存在或不可编辑'
            return
        }
        reviewStatus.value = Number(drama.reviewStatus)
        form.title = drama.title || ''
        form.subtitle = drama.subtitle || ''
        form.coverUrl = drama.coverUrl || ''
        form.description = drama.description || ''
        form.categoryId = Number(drama.categoryId) || form.categoryId
        form.actors = drama.actors || ''
        episodes.value = (detail.episodes || []).map((episode) => ({
            episodeNo: episode.episodeNo,
            title: episode.title || '',
            durationSeconds: Number(episode.durationSeconds) || 0,
            pricePoints: Number(episode.pricePoints) || 0,
            status: Number(episode.status)
        }))
        if (!episodes.value.length) {
            episodes.value = [emptyEpisode(1)]
        }
    } catch (cause) {
        error.value = cause?.message || '短剧加载失败'
    } finally {
        loading.value = false
    }
}

function addEpisode() {
    const maxNo = episodes.value.reduce(
        (max, episode) => Math.max(max, Number(episode.episodeNo) || 0), 0)
    episodes.value.push(emptyEpisode(maxNo + 1))
}

function removeEpisode(index) {
    if (episodes.value.length <= 1) {
        toast.push('至少保留一集')
        return
    }
    episodes.value.splice(index, 1)
}

/**
 * 校验表单并组装后端请求。
 */
function buildPayload() {
    const title = form.title.trim()
    if (!title) {
        toast.push('请填写短剧标题')
        return null
    }
    if (!Number(form.categoryId)) {
        toast.push('请选择分类')
        return null
    }
    if (!episodes.value.length) {
        toast.push('至少添加一集')
        return null
    }
    const seen = new Set()
    const payloadEpisodes = []
    for (let index = 0; index < episodes.value.length; index += 1) {
        const episode = episodes.value[index]
        const episodeNo = Number(episode.episodeNo)
        if (!Number.isFinite(episodeNo) || episodeNo < 1) {
            toast.push(`第 ${index + 1} 行集数不合法`)
            return null
        }
        if (seen.has(episodeNo)) {
            toast.push('集数不能重复')
            return null
        }
        seen.add(episodeNo)
        payloadEpisodes.push({
            episodeNo,
            title: String(episode.title || '').trim(),
            durationSeconds: Math.max(0, Number(episode.durationSeconds) || 0),
            pricePoints: Math.max(0, Number(episode.pricePoints) || 0),
            status: Number(episode.status) === 0 ? 0 : 1
        })
    }
    return {
        title,
        subtitle: form.subtitle.trim(),
        coverUrl: form.coverUrl.trim(),
        description: form.description.trim(),
        categoryId: Number(form.categoryId),
        actors: form.actors.trim(),
        episodes: payloadEpisodes
    }
}

/**
 * 保存草稿，可选择保存后立即提交审核。
 */
async function save(submitAfter) {
    if (saving.value || !canSave.value) {
        return
    }
    const payload = buildPayload()
    if (!payload) {
        return
    }
    saving.value = true
    try {
        let targetId = dramaId.value
        if (isNew.value) {
            const detail = await createDrama(payload)
            targetId = detail?.drama?.dramaId || 0
        } else {
            await updateDrama(targetId, payload)
        }
        if (submitAfter && targetId) {
            await submitDrama(targetId)
            toast.success('已保存并提交审核')
        } else {
            toast.success('草稿已保存')
        }
        router.push('/creator/dramas')
    } catch (cause) {
        toast.push(cause?.message || '保存失败')
    } finally {
        saving.value = false
    }
}

load()
</script>

<template>
    <div class="sub-page">
        <PageHeader
            :title="isNew ? '新建短剧' : '编辑短剧'"
            back-to="/creator/dramas"
        />

        <main class="app-main edit-page is-narrow">
            <div v-if="loading" class="edit-skeleton">
                <SkeletonBlock height="180px" radius="16px"/>
                <SkeletonBlock height="220px" radius="16px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="load"/>

            <template v-else>
                <p v-if="!canSave" class="readonly-hint">
                    当前状态为{{ reviewStatus === 1 ? '审核中' : reviewStatus === 2 ? '已上架' : '已下架' }}，
                    只有草稿或驳回状态可以编辑。
                </p>

                <section class="form-card">
                    <h2>基础信息</h2>
                    <div class="field-grid">
                        <label class="field">
                            <span>标题</span>
                            <input v-model="form.title" maxlength="128" placeholder="短剧标题">
                        </label>
                        <label class="field">
                            <span>副标题</span>
                            <input v-model="form.subtitle" maxlength="255" placeholder="一句话简介">
                        </label>
                        <label class="field">
                            <span>封面地址</span>
                            <input v-model="form.coverUrl" maxlength="512" placeholder="留空使用渐变封面">
                        </label>
                        <label class="field">
                            <span>主演</span>
                            <input v-model="form.actors" maxlength="255" placeholder="逗号分隔">
                        </label>
                        <label class="field">
                            <span>分类</span>
                            <select v-if="categories.length" v-model.number="form.categoryId">
                                <option v-for="category in categories" :key="category.id" :value="category.id">
                                    {{ category.name }}
                                </option>
                            </select>
                            <input v-else v-model.number="form.categoryId" type="number" min="1">
                        </label>
                        <label class="field field-wide">
                            <span>简介</span>
                            <textarea v-model="form.description" maxlength="1000" rows="4"
                                      placeholder="剧情简介"></textarea>
                        </label>
                    </div>
                </section>

                <section class="form-card">
                    <div class="section-title">
                        <h2>剧集</h2>
                        <button class="text-btn" type="button" @click="addEpisode">添加剧集</button>
                    </div>

                    <div class="episode-list">
                        <div v-for="(episode, index) in episodes" :key="index" class="episode-row">
                            <div class="episode-head">
                                <strong>第 {{ index + 1 }} 行</strong>
                                <button
                                    class="text-btn danger"
                                    type="button"
                                    @click="removeEpisode(index)"
                                >
                                    移除
                                </button>
                            </div>
                            <div class="episode-fields">
                                <label class="field">
                                    <span>集数</span>
                                    <input v-model.number="episode.episodeNo" type="number" min="1">
                                </label>
                                <label class="field episode-title">
                                    <span>标题</span>
                                    <input v-model="episode.title" maxlength="128">
                                </label>
                                <label class="field">
                                    <span>时长（秒）</span>
                                    <input v-model.number="episode.durationSeconds" type="number" min="0">
                                </label>
                                <label class="field">
                                    <span>解锁积分</span>
                                    <input v-model.number="episode.pricePoints" type="number" min="0">
                                </label>
                                <label class="field">
                                    <span>状态</span>
                                    <select v-model.number="episode.status">
                                        <option :value="1">上线</option>
                                        <option :value="0">下线</option>
                                    </select>
                                </label>
                            </div>
                        </div>
                    </div>
                </section>

                <div class="edit-actions">
                    <button
                        class="btn btn-ghost btn-block"
                        type="button"
                        :disabled="saving || !canSave"
                        @click="save(false)"
                    >
                        {{ saving ? '保存中…' : '保存草稿' }}
                    </button>
                    <button
                        class="btn btn-primary btn-block"
                        type="button"
                        :disabled="saving || !canSave"
                        @click="save(true)"
                    >
                        {{ saving ? '提交中…' : '保存并提交审核' }}
                    </button>
                </div>
            </template>
        </main>
    </div>
</template>

<style scoped>
.edit-page {
    display: grid;
    gap: 14px;
}

.edit-skeleton {
    display: grid;
    gap: 12px;
}

.readonly-hint {
    margin: 0;
    padding: 10px 12px;
    border: 1px solid rgba(255, 179, 63, 0.35);
    border-radius: 12px;
    background: rgba(255, 179, 63, 0.08);
    color: var(--brand-2);
    font-size: 11px;
    line-height: 1.6;
}

.form-card {
    display: grid;
    gap: 12px;
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
}

.form-card h2 {
    margin: 0;
    font-size: 15px;
}

.field-wide {
    grid-column: 1 / -1;
}

.field select {
    height: 48px;
    padding: 0 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: rgba(0, 0, 0, 0.28);
    color: var(--text);
    font-size: 15px;
}

.episode-list {
    display: grid;
    gap: 10px;
}

.episode-row {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.025);
}

.episode-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    color: var(--muted);
    font-size: 11px;
}

.episode-fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
}

.episode-title {
    grid-column: 1 / -1;
}

.text-btn {
    padding: 0;
    border: none;
    background: none;
    color: var(--brand-2);
    font-size: 11px;
}

.text-btn.danger {
    color: var(--danger);
}

.edit-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
}

@media (min-width: 560px) {
    .episode-fields {
        grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    .episode-title {
        grid-column: span 2;
    }
}

/* ---------- 桌面端（≥1024）：表单两列，剧集单行编辑 ---------- */

@media (min-width: 1024px) {
    .edit-page {
        gap: 18px;
    }

    .form-card {
        gap: 16px;
        padding: 22px 24px;
    }

    .form-card h2 {
        font-size: 16px;
    }

    .field-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 14px 16px;
    }

    .field-wide {
        grid-column: 1 / -1;
    }

    .episode-row {
        padding: 14px 16px;
    }

    .episode-fields {
        grid-template-columns: 88px minmax(0, 1fr) 118px 108px 96px;
        align-items: end;
    }

    .episode-title {
        grid-column: auto;
    }

    .episode-head {
        font-size: 12px;
    }

    .text-btn {
        font-size: 12px;
    }

    /* 保存操作固定在视口底部，长表单不用滚回底部 */
    .edit-actions {
        position: sticky;
        bottom: 0;
        z-index: 20;
        grid-template-columns: auto auto;
        justify-content: end;
        gap: 12px;
        padding: 14px 0;
        background: linear-gradient(180deg, transparent, rgba(9, 11, 16, 0.88) 42%);
    }

    .edit-actions .btn {
        width: auto;
        min-width: 160px;
        padding: 0 22px;
    }
}
</style>
