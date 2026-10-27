<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchCategories, searchDramas } from '../api/drama'
import { usePagedList } from '../composables/usePagedList'
import DramaCard from '../components/DramaCard.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const route = useRoute()
const router = useRouter()

// 1. 顶部栏搜索会带上 keyword 查询参数，首次进入直接回填
const initialKeyword = typeof route.query.keyword === 'string'
    ? route.query.keyword.trim().slice(0, 50)
    : ''

const categories = ref([])
const keywordInput = ref(initialKeyword)
const keyword = ref(initialKeyword)
const activeCategory = ref(null)
const sort = ref('latest')

const {
    items,
    total,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    refresh,
    loadMore
} = usePagedList(
    ({ pageNo, pageSize }) => searchDramas({
        keyword: keyword.value,
        categoryId: activeCategory.value,
        sort: sort.value,
        pageNo,
        pageSize
    }),
    { pageSize: 12 }
)

/**
 * 加载分类；失败时降级为不显示筛选栏，不阻断搜索。
 */
async function loadCategories() {
    try {
        categories.value = await fetchCategories()
    } catch {
        categories.value = []
    }
}

/**
 * 提交关键词搜索：同步地址栏，地址未变化时直接刷新结果。
 */
function submitSearch() {
    const value = keywordInput.value.trim()
    keyword.value = value

    const query = value ? { keyword: value } : {}
    const target = router.resolve({ name: 'search', query })

    // 2. RouterView 以 fullPath 为 key，查询变化会重挂载并自动请求；
    //    查询没变（例如重复搜索同一关键词）时手动刷新即可
    if (target.fullPath === route.fullPath) {
        refresh()
        return
    }
    router.replace({ name: 'search', query })
}

/**
 * 切换分类；再次点击已选分类时取消筛选。
 */
function selectCategory(categoryId) {
    activeCategory.value = activeCategory.value === categoryId ? null : categoryId
    refresh()
}

/**
 * 切换最新/最热排序。
 */
function changeSort(value) {
    if (sort.value === value) {
        return
    }
    sort.value = value
    refresh()
}

onMounted(loadCategories)
</script>

<template>
    <div class="sub-page">
        <PageHeader title="发现" back-to="/"/>

        <main class="app-main search-page">
            <form class="search-bar" role="search" @submit.prevent="submitSearch">
                <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="2" stroke-linecap="round" aria-hidden="true">
                    <circle cx="11" cy="11" r="7"/>
                    <path d="M16.5 16.5L21 21"/>
                </svg>
                <input
                    v-model="keywordInput"
                    type="search"
                    maxlength="50"
                    placeholder="搜索短剧、演员、剧情"
                    aria-label="搜索关键词"
                />
                <button class="btn btn-primary btn-small" type="submit">搜索</button>
            </form>

            <div class="search-body" :class="{ 'has-filters': categories.length }">
                <aside v-if="categories.length" class="search-filters" aria-label="分类筛选">
                    <h2 class="filter-title">分类</h2>
                    <div class="chip-row">
                        <button
                            class="chip"
                            :class="{ 'is-active': activeCategory === null }"
                            type="button"
                            @click="selectCategory(null)"
                        >
                            全部
                        </button>
                        <button
                            v-for="category in categories"
                            :key="category.id"
                            class="chip"
                            :class="{ 'is-active': activeCategory === category.id }"
                            type="button"
                            @click="selectCategory(category.id)"
                        >
                            {{ category.name }}
                        </button>
                    </div>

                    <div class="filter-sort">
                        <h2 class="filter-title">排序</h2>
                        <div class="sort-switch" role="group" aria-label="排序方式">
                            <button
                                class="sort-option"
                                :class="{ 'is-active': sort === 'latest' }"
                                type="button"
                                @click="changeSort('latest')"
                            >
                                最新
                            </button>
                            <button
                                class="sort-option"
                                :class="{ 'is-active': sort === 'hot' }"
                                type="button"
                                @click="changeSort('hot')"
                            >
                                最热
                            </button>
                        </div>
                    </div>
                </aside>

                <section class="search-results">
                    <div class="search-toolbar">
                        <div class="sort-switch toolbar-sort" role="group" aria-label="排序方式">
                            <button
                                class="sort-option"
                                :class="{ 'is-active': sort === 'latest' }"
                                type="button"
                                @click="changeSort('latest')"
                            >
                                最新
                            </button>
                            <button
                                class="sort-option"
                                :class="{ 'is-active': sort === 'hot' }"
                                type="button"
                                @click="changeSort('hot')"
                            >
                                最热
                            </button>
                        </div>
                        <span class="result-count">{{ loading ? '搜索中…' : `共 ${total} 部` }}</span>
                    </div>

                    <div v-if="loading" class="result-grid">
                        <SkeletonBlock v-for="index in 6" :key="index" height="216px" radius="14px"/>
                    </div>

                    <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

                    <EmptyState
                        v-else-if="!items.length"
                        title="没有找到相关短剧"
                        description="换个关键词或分类试试，也可以回到首页看看热播。"
                        action-text="回首页"
                        @action="router.push('/')"
                    />

                    <template v-else>
                        <div class="result-grid">
                            <DramaCard v-for="item in items" :key="item.dramaId" :drama="item"/>
                        </div>

                        <LoadMore
                            :loading="loadingMore"
                            :has-more="hasMore"
                            :error="loadMoreError"
                            :total="total"
                            @load="loadMore"
                        />
                    </template>
                </section>
            </div>
        </main>
    </div>
</template>

<style scoped>
.search-page {
    gap: 14px;
}

.search-bar {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 6px 6px 13px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
    transition: border-color 150ms ease, box-shadow 150ms ease;
}

.search-bar:focus-within {
    border-color: rgba(255, 179, 63, 0.55);
    box-shadow: 0 0 0 3px rgba(255, 179, 63, 0.1);
}

.search-icon {
    flex: none;
    width: 18px;
    height: 18px;
    color: var(--brand-2);
}

.search-bar input {
    min-width: 0;
    flex: 1;
    height: 34px;
    padding: 0;
    border: none;
    background: transparent;
    color: var(--text);
    font-size: 16px;
}

.search-bar input::placeholder {
    color: rgba(255, 255, 255, 0.28);
}

.search-bar input:focus {
    outline: none;
}

.search-bar .btn {
    flex: none;
}

.search-body {
    display: grid;
    gap: 14px;
}

.search-results {
    display: grid;
    min-width: 0;
    gap: 14px;
}

/* 移动端：分类横向滚动，排序放在工具栏 */
.filter-title,
.filter-sort {
    display: none;
}

.chip-row {
    display: flex;
    gap: 8px;
    margin: 0 -16px;
    padding: 0 16px 2px;
    overflow-x: auto;
    scrollbar-width: none;
}

.chip-row::-webkit-scrollbar {
    display: none;
}

.search-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

.sort-switch {
    display: inline-flex;
    gap: 2px;
    padding: 3px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.03);
}

.sort-option {
    height: 28px;
    padding: 0 12px;
    border: none;
    border-radius: 999px;
    background: transparent;
    color: var(--muted);
    font-size: 12px;
    transition: background 150ms ease, color 150ms ease;
}

.sort-option.is-active {
    background: var(--brand-soft);
    color: var(--brand-2);
    font-weight: 700;
}

.result-count {
    color: var(--muted);
    font-size: 11px;
}

.result-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px 12px;
}

.result-grid :deep(.drama-card) {
    width: 100%;
}

/* ---------- 桌面端（≥1024）：左侧筛选栏 + 结果网格 ---------- */

@media (min-width: 1024px) {
    .search-body {
        grid-template-columns: 240px minmax(0, 1fr);
        align-items: start;
        gap: 28px;
    }

    /* 分类加载失败时不保留空列 */
    .search-body:not(.has-filters) {
        grid-template-columns: minmax(0, 1fr);
    }

    .search-filters {
        position: sticky;
        top: calc(var(--topbar-height) + 76px);
        display: grid;
        gap: 10px;
        padding: 16px;
        border: 1px solid var(--line);
        border-radius: 16px;
        background: var(--panel);
    }

    .filter-title {
        display: block;
        color: rgba(255, 255, 255, 0.34);
        font-size: 10px;
        font-weight: 700;
        letter-spacing: 0.14em;
        text-transform: uppercase;
    }

    .filter-sort {
        display: grid;
        gap: 8px;
        margin-top: 8px;
        padding-top: 14px;
        border-top: 1px solid var(--line);
    }

    .chip-row {
        flex-wrap: wrap;
        margin: 0;
        padding: 0;
        overflow: visible;
    }

    .toolbar-sort {
        display: none;
    }

    .search-body:not(.has-filters) .toolbar-sort {
        display: inline-flex;
    }

    .result-grid {
        grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
        gap: 18px 14px;
    }
}
</style>
