<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchPoints } from '../api/order'
import { fetchStats } from '../api/interaction'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'
import { formatDate } from '../utils/format'
import AppModal from '../components/AppModal.vue'
import PageHeader from '../components/PageHeader.vue'
import UserAvatar from '../components/UserAvatar.vue'

const auth = useAuthStore()
const toast = useToastStore()
const router = useRouter()

const points = ref(null)
const pointsLoading = ref(true)
const pointsError = ref('')

const stats = ref(null)
const statsLoading = ref(true)
const statsError = ref('')

const editOpen = ref(false)
const editForm = reactive({ nickname: '', avatarUrl: '', bio: '' })
const editError = ref('')
const saving = ref(false)

const joinedText = computed(() => formatDate(auth.user?.createTime))

const statTiles = computed(() => [
    { key: 'likes', label: '点赞短剧', value: stats.value?.likedDramaCount, to: '/me/likes', tone: 'heart' },
    { key: 'favorites', label: '我的收藏', value: stats.value?.favoriteCount, to: '/me/favorites', tone: 'star' },
    { key: 'comments', label: '我的评论', value: stats.value?.commentCount, to: '/me/comments', tone: 'comment' },
    { key: 'history', label: '观看记录', value: stats.value?.historyCount, to: '/history', tone: 'clock' }
])

async function loadProfile() {
    try {
        await auth.loadMe()
    } catch (cause) {
        if (cause?.status !== 401) {
            toast.push(cause?.message || '资料刷新失败')
        }
    }
}

async function loadPoints() {
    pointsLoading.value = true
    pointsError.value = ''
    try {
        points.value = await fetchPoints()
    } catch (cause) {
        pointsError.value = cause?.message || '积分加载失败'
    } finally {
        pointsLoading.value = false
    }
}

async function loadStats() {
    statsLoading.value = true
    statsError.value = ''
    try {
        stats.value = await fetchStats()
    } catch (cause) {
        statsError.value = cause?.message || '统计加载失败'
    } finally {
        statsLoading.value = false
    }
}

function openEdit() {
    editForm.nickname = auth.user?.nickname || ''
    editForm.avatarUrl = auth.user?.avatarUrl || ''
    editForm.bio = auth.user?.bio || ''
    editError.value = ''
    editOpen.value = true
}

/**
 * 校验与后端保持一致：昵称 1-20，头像 512，简介 255。
 */
async function saveProfile() {
    const nickname = editForm.nickname.trim()
    const avatarUrl = editForm.avatarUrl.trim()
    const bio = editForm.bio.trim()

    if (!nickname) {
        editError.value = '昵称不能为空'
        return
    }
    if (nickname.length > 20) {
        editError.value = '昵称最多 20 个字符'
        return
    }
    if (avatarUrl.length > 512) {
        editError.value = '头像 URL 最多 512 个字符'
        return
    }
    if (bio.length > 255) {
        editError.value = '简介最多 255 个字符'
        return
    }

    saving.value = true
    editError.value = ''
    try {
        await auth.updateProfile({ nickname, avatarUrl, bio })
        toast.success('资料已更新')
        editOpen.value = false
    } catch (cause) {
        editError.value = cause?.message || '保存失败'
    } finally {
        saving.value = false
    }
}

function logout() {
    auth.clear()
    toast.success('已退出登录')
    router.replace({ name: 'login' })
}

onMounted(() => {
    loadProfile()
    loadPoints()
    loadStats()
})
</script>

<template>
    <div class="tab-page">
        <PageHeader title="我的" :show-back="false"/>

        <main class="app-main profile-main">
            <aside class="profile-aside">
                <section class="profile-card">
                <div class="profile-head">
                    <UserAvatar
                        :name="auth.displayName"
                        :src="auth.user?.avatarUrl"
                        :size="58"
                    />
                    <div class="profile-copy">
                        <div class="name-row">
                            <h1>{{ auth.displayName }}</h1>
                            <span class="role-chip">{{ auth.roleLabel }}</span>
                        </div>
                        <p class="phone">{{ auth.maskedPhone || '—' }}</p>
                    </div>
                    <button class="edit-btn" type="button" @click="openEdit">编辑</button>
                </div>
                <p class="bio">{{ auth.user?.bio || '还没有填写简介' }}</p>
                <p class="joined">加入于 {{ joinedText || '—' }}</p>
            </section>

            <section class="points-card">
                <div class="points-main">
                    <span>积分余额</span>
                    <strong v-if="pointsLoading">…</strong>
                    <strong v-else-if="pointsError">加载失败</strong>
                    <strong v-else-if="points?.exists">{{ points.balance }}</strong>
                    <strong v-else>未开通</strong>
                </div>
                <div class="points-side">
                    <span v-if="!pointsError" class="points-hint">
                        {{ points?.exists === false ? '充值后自动开通积分账户' : '用于解锁付费剧集' }}
                    </span>
                    <button v-if="pointsError" class="text-btn" type="button" @click="loadPoints">
                        重试
                    </button>
                    <button v-else class="text-btn" type="button" @click="router.push('/recharge')">
                        充值
                    </button>
                </div>
            </section>
            </aside>

            <div class="profile-content">
            <section class="stats-section">
                <div class="section-title">
                    <h2>我的数据</h2>
                    <button v-if="statsError" class="text-btn" type="button" @click="loadStats">
                        重试
                    </button>
                </div>
                <div class="stats-grid">
                    <button
                        v-for="tile in statTiles"
                        :key="tile.key"
                        class="stat-card"
                        type="button"
                        @click="router.push(tile.to)"
                    >
                        <span class="stat-icon" :class="`tone-${tile.tone}`" aria-hidden="true">
                            <svg v-if="tile.tone === 'heart'" viewBox="0 0 24 24" fill="none"
                                 stroke="currentColor" stroke-width="1.8" stroke-linecap="round"
                                 stroke-linejoin="round">
                                <path d="M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 6.8 4.5 4.5 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z"/>
                            </svg>
                            <svg v-else-if="tile.tone === 'star'" viewBox="0 0 24 24" fill="none"
                                 stroke="currentColor" stroke-width="1.8" stroke-linecap="round"
                                 stroke-linejoin="round">
                                <path d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z"/>
                            </svg>
                            <svg v-else-if="tile.tone === 'comment'" viewBox="0 0 24 24" fill="none"
                                 stroke="currentColor" stroke-width="1.8" stroke-linecap="round"
                                 stroke-linejoin="round">
                                <path d="M4 5h16v11H9l-5 4z"/>
                            </svg>
                            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                                <circle cx="12" cy="12" r="9"/>
                                <path d="M12 7.5v5l3.2 1.8"/>
                            </svg>
                        </span>
                        <strong>{{ statsLoading ? '—' : (statsError ? '—' : (tile.value ?? 0)) }}</strong>
                        <span class="stat-label">{{ tile.label }}</span>
                    </button>
                </div>
            </section>

            <section class="menu-card">
                <button
                    v-if="auth.user?.role === 'CREATOR' || auth.user?.role === 'ADMIN'"
                    class="menu-row"
                    type="button"
                    @click="router.push('/creator/dramas')"
                >
                    <span>创作者中心</span><span class="chevron">›</span>
                </button>
                <button
                    v-if="auth.user?.role === 'ADMIN'"
                    class="menu-row"
                    type="button"
                    @click="router.push('/admin/reviews')"
                >
                    <span>审核台</span><span class="chevron">›</span>
                </button>
                <button
                    v-if="auth.user?.role === 'ADMIN'"
                    class="menu-row"
                    type="button"
                    @click="router.push('/admin/comments')"
                >
                    <span>评论治理</span><span class="chevron">›</span>
                </button>
                <button class="menu-row" type="button" @click="router.push('/recharge')">
                    <span>充值中心</span><span class="chevron">›</span>
                </button>
                <button class="menu-row" type="button" @click="router.push('/me/favorites')">
                    <span>我的收藏</span><span class="chevron">›</span>
                </button>
                <button class="menu-row" type="button" @click="router.push('/history')">
                    <span>观看记录</span><span class="chevron">›</span>
                </button>
                <button class="menu-row" type="button" @click="router.push('/me/unlocks')">
                    <span>已解锁剧集</span><span class="chevron">›</span>
                </button>
                <button class="menu-row" type="button" @click="router.push('/me/comments')">
                    <span>我的评论</span><span class="chevron">›</span>
                </button>
                <button class="menu-row" type="button" @click="router.push('/me/likes')">
                    <span>点赞的短剧</span><span class="chevron">›</span>
                </button>
            </section>

            <button class="btn btn-ghost btn-block" type="button" @click="logout">
                退出登录
            </button>
            </div>
        </main>

        <AppModal v-model="editOpen" title="编辑资料">
            <form class="profile-form" @submit.prevent="saveProfile">
                <div class="avatar-preview">
                    <UserAvatar
                        :name="editForm.nickname || auth.displayName"
                        :src="editForm.avatarUrl"
                        :size="52"
                    />
                    <span>头像预览</span>
                </div>

                <label class="field">
                    <span>昵称</span>
                    <input v-model.trim="editForm.nickname" maxlength="20" placeholder="最多 20 个字符">
                </label>

                <label class="field">
                    <span>头像 URL</span>
                    <input v-model.trim="editForm.avatarUrl" maxlength="512" placeholder="留空使用昵称首字头像">
                </label>

                <label class="field">
                    <span>简介</span>
                    <textarea
                        v-model="editForm.bio"
                        maxlength="255"
                        rows="3"
                        placeholder="简单介绍一下自己"
                    ></textarea>
                    <small>{{ editForm.bio.length }}/255</small>
                </label>

                <p v-if="editError" class="form-error">{{ editError }}</p>
            </form>

            <template #footer>
                <button class="btn btn-ghost" type="button" @click="editOpen = false">取消</button>
                <button class="btn btn-primary" type="button" :disabled="saving" @click="saveProfile">
                    {{ saving ? '保存中…' : '保存' }}
                </button>
            </template>
        </AppModal>
    </div>
</template>

<style scoped>
.profile-main {
    gap: 14px;
}

.profile-card,
.points-card,
.stats-section,
.menu-card {
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
}

.profile-head {
    display: flex;
    align-items: center;
    gap: 13px;
}

.profile-copy {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 4px;
}

.name-row {
    display: flex;
    align-items: center;
    gap: 8px;
}

.name-row h1 {
    overflow: hidden;
    font-size: 19px;
    font-weight: 800;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.role-chip {
    flex: none;
    padding: 2px 7px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-2);
    font-size: 9px;
}

.phone {
    color: var(--muted);
    font-size: 11px;
    letter-spacing: 0.04em;
}

.edit-btn {
    flex: none;
    padding: 6px 10px;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: transparent;
    color: var(--brand-2);
    font-size: 11px;
}

.bio {
    margin-top: 12px;
    color: #c2cbdb;
    font-size: 12px;
    line-height: 1.7;
}

.joined {
    margin-top: 5px;
    color: rgba(255, 255, 255, 0.3);
    font-size: 10px;
}

.points-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    background:
        radial-gradient(240px 120px at 100% 0%, rgba(255, 179, 63, 0.16), transparent 70%),
        var(--panel);
}

.points-main {
    display: flex;
    align-items: baseline;
    gap: 9px;
    color: var(--muted);
    font-size: 12px;
}

.points-side {
    display: grid;
    justify-items: end;
    gap: 5px;
}

.points-main strong {
    color: var(--brand-2);
    font-size: 24px;
    font-weight: 900;
    letter-spacing: -0.01em;
}

.points-hint {
    color: rgba(255, 255, 255, 0.3);
    font-size: 10px;
}

.text-btn {
    padding: 0;
    border: none;
    background: none;
    color: var(--brand-2);
    font-size: 11px;
    text-decoration: underline;
}

.stats-section {
    display: grid;
    gap: 10px;
    padding: 14px;
}

.stats-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 7px;
}

.stat-card {
    display: grid;
    justify-items: center;
    gap: 4px;
    padding: 10px 4px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.03);
    color: var(--text);
}

.stat-icon {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border-radius: 9px;
}

.stat-icon svg {
    width: 15px;
    height: 15px;
}

.tone-heart {
    background: rgba(255, 92, 61, 0.14);
    color: #ff8c74;
}

.tone-star {
    background: rgba(255, 179, 63, 0.14);
    color: var(--brand-2);
}

.tone-comment {
    background: rgba(94, 158, 255, 0.14);
    color: #8ab4ff;
}

.tone-clock {
    background: rgba(71, 209, 140, 0.13);
    color: var(--ok);
}

.stat-card strong {
    font-size: 16px;
    font-weight: 800;
}

.stat-label {
    color: var(--muted);
    font-size: 9px;
    white-space: nowrap;
}

.menu-card {
    display: grid;
    padding: 4px 14px;
}

.menu-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 13px 0;
    border: none;
    border-bottom: 1px solid var(--line);
    background: none;
    color: var(--text);
    font-size: 13px;
    text-align: left;
}

.menu-row:last-child {
    border-bottom: none;
}

.chevron {
    color: rgba(255, 255, 255, 0.3);
    font-size: 17px;
    line-height: 1;
}

.profile-form {
    display: grid;
    gap: 12px;
}

.avatar-preview {
    display: flex;
    align-items: center;
    gap: 10px;
    color: var(--muted);
    font-size: 11px;
}

.field small {
    color: rgba(255, 255, 255, 0.28);
    font-size: 10px;
    text-align: right;
}

.profile-aside,
.profile-content {
    display: grid;
    gap: 14px;
}

/* ---------- 桌面端（≥1024）：左侧资料卡 + 右侧内容 ---------- */

@media (min-width: 1024px) {
    .profile-main {
        grid-template-columns: 340px minmax(0, 1fr);
        align-items: start;
        gap: 24px;
    }

    .profile-aside {
        position: sticky;
        top: calc(var(--topbar-height) + 24px);
    }

    .profile-card,
    .points-card {
        padding: 20px;
    }

    /* 积分卡在窄侧栏里改为上下排布 */
    .points-card {
        flex-direction: column;
        align-items: flex-start;
        gap: 14px;
    }

    .points-side {
        justify-items: start;
    }

    .points-main strong {
        font-size: 30px;
    }

    .stats-section {
        padding: 18px;
    }

    .stat-card {
        padding: 16px 6px;
    }

    .stat-card strong {
        font-size: 20px;
    }

    .stat-label {
        font-size: 11px;
    }

    /* 菜单改为两列卡片，避免长列表在宽屏上留白 */
    .menu-card {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 10px;
        padding: 0;
        border: none;
        background: transparent;
    }

    .menu-row {
        padding: 15px 16px;
        border: 1px solid var(--line);
        border-radius: 14px;
        background: var(--panel);
        font-size: 13px;
    }

    .menu-row:last-child {
        border-bottom: 1px solid var(--line);
    }

    .profile-content > .btn {
        justify-self: start;
        width: auto;
        min-width: 160px;
    }
}
</style>
