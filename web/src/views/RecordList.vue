<script setup lang="ts">
// 列表页 = 首页（基线 §2）。数据只经由 api/records.ts 的 listRecords，页面里不出现 fetch。
// 4.2 只改结构与外观：统一 page-head / .control 筛选区 / 卡片网格 / 三段状态；
// 请求、URL 状态、竞态丢弃一律沿用 4.1 实现（未改业务逻辑）。
import { computed, ref, watch } from 'vue'
import { listRecords } from '@/api/records'
import RecordCard from '@/components/record/RecordCard.vue'
import RecordFilterBar from '@/components/filter/RecordFilterBar.vue'
import PaginationBar from '@/components/filter/PaginationBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import LoadingBlock from '@/components/common/LoadingBlock.vue'
import { useRequest } from '@/composables/useRequest'
import { useRouteQueryState } from '@/composables/useRouteQueryState'
import { toRecordQuery } from '@/types/domain'
import type { ListFilterState } from '@/types/domain'
import type { RecordQuery } from '@/types/api'

const DEFAULT_FILTER: ListFilterState = { type: '', status: 'PENDING', categoryId: '', keyword: '' }
const DEFAULT_PAGING = { page: 1, pageSize: 10 }

// URL 即状态：page/pageSize 的合法性由路由守卫归一，这里不再兜一层
const state = useRouteQueryState({ ...DEFAULT_FILTER, ...DEFAULT_PAGING })
const { data, error, loading, run } = useRequest(listRecords, { immediate: false })

const items = computed(() => data.value?.list ?? [])
const total = computed(() => data.value?.total ?? 0)
const page = computed(() => data.value?.page ?? state.value.page)
const pageSize = computed(() => data.value?.pageSize ?? state.value.pageSize)

const apiQuery = computed<RecordQuery>(() => ({
  ...toRecordQuery(state.value),
  page: state.value.page,
  pageSize: state.value.pageSize
}))

// 竞态由 useRequest 的 sequence 丢弃过期响应，这里不做额外节流
watch(
  apiQuery,
  (query) => {
    void run(query)
  },
  { immediate: true, deep: true }
)

function updateFilter(next: ListFilterState): void {
  // 除了页码以外的条件变了就回第一页，否则「第 3 页 + 新筛选」大概率落在空页上
  const onlyPagingUnchanged =
    next.type === state.value.type &&
    next.status === state.value.status &&
    next.categoryId === state.value.categoryId &&
    next.keyword === state.value.keyword
  state.value = {
    ...next,
    page: onlyPagingUnchanged ? state.value.page : 1,
    pageSize: state.value.pageSize
  }
}

function updatePage(nextPage: number): void {
  state.value = { ...state.value, page: nextPage }
}

/**
 * 重置筛选 = 清空全部条件 + 页码归位，必须显式回第 1 页。
 * 不能复用 updateFilter：默认筛选下翻页后四字段与当前值相同，会被当成「仅翻页」保留页码。
 */
function resetFilter(): void {
  state.value = { ...DEFAULT_FILTER, page: DEFAULT_PAGING.page, pageSize: state.value.pageSize }
}

/** 错误态的重试：重发当前筛选条件下的同一个请求（不引入新的调用路径） */
function reload(): void {
  void run(apiQuery.value)
}

/** 本人关闭启事后：该行不再属于公共列表，重拉当前页并给一次明确反馈 */
const closedNotice = ref<string>('')

function handleChanged(payload: { id: number; status: string }): void {
  if (payload.status !== 'CLOSED') return
  closedNotice.value = `启事 #${payload.id} 已关闭，不再出现在公共列表。可在「我的启事」查看。`
  reload()
}
</script>

<template>
  <section class="record-list">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">启事列表</h1>
        <p class="page-head__desc">按类型、状态、分类筛选；点卡片看详情并匹配相关启事。</p>
      </div>
      <div class="page-head__actions">
        <RouterLink :to="{ name: 'RecordCreate' }" class="btn btn--primary">
          + 发布启事
        </RouterLink>
      </div>
    </header>

    <!-- 不用 v-model：改筛选走 updateFilter（回第 1 页），重置走 resetFilter（显式归位） -->
    <RecordFilterBar
      :model-value="state"
      @update:model-value="updateFilter"
      @reset="resetFilter"
    />

    <div class="record-list__body">
      <p v-if="closedNotice" class="alert alert--success" role="status" data-testid="list-closed-notice">
        <span class="alert__title">已关闭</span>
        <span class="alert__text">{{ closedNotice }}</span>
        <RouterLink class="alert__action" :to="{ name: 'MyRecords' }">去我的启事</RouterLink>
      </p>

      <ErrorBanner v-if="error" :error="error" retry-label="重试" @retry="reload" />

      <LoadingBlock v-else-if="loading" :rows="3" label="正在加载启事列表" />

      <EmptyState
        v-else-if="items.length === 0"
        title="暂无符合条件的启事"
        description="换个筛选条件，或发布一条新的启事"
        action-text="也可以点上方「重置筛选」清空全部条件"
        action-label="去发布一条启事"
        :to="{ name: 'RecordCreate' }"
      />

      <ul v-else class="record-list__grid">
        <li v-for="item in items" :key="item.id">
          <RecordCard :item="item" @changed="handleChanged" />
        </li>
      </ul>

      <PaginationBar
        v-if="!loading && !error && items.length > 0"
        :page="page"
        :page-size="pageSize"
        :total="total"
        @update:page="updatePage"
      />
    </div>
  </section>
</template>

<style scoped>
.record-list__body {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  margin-top: var(--sp-4);
}

.record-list__grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-3);
}

@media (min-width: 768px) {
  .record-list__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--sp-4);
  }
}

@media (min-width: 1120px) {
  .record-list__grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
