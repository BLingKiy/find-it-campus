<script setup lang="ts">
// 列表页 = 首页（基线 §2）。数据只经由 api/records.ts 的 listRecords，页面里不出现 fetch
import { computed, watch } from 'vue'
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
</script>

<template>
  <section class="record-list">
    <header class="record-list__head">
      <h1 class="record-list__title">启事列表</h1>
      <RouterLink :to="{ name: 'RecordCreate' }" class="btn btn--primary record-list__cta">
        + 发布启事
      </RouterLink>
    </header>

    <!-- 不用 v-model：筛选变化要同时重置页码，交给 updateFilter 一处处理 -->
    <RecordFilterBar
      :model-value="state"
      @update:model-value="updateFilter"
    />

    <ErrorBanner v-if="error" :error="error" class="u-mt" />
    <LoadingBlock v-if="loading" :rows="3" class="u-mt" />
    <EmptyState
      v-else-if="!error && items.length === 0"
      class="u-mt"
      title="暂无符合条件的启事"
      description="换个筛选条件，或发布一条新的启事"
      action-text="点上方「重置」清空筛选试试"
    />

    <ul v-else class="record-list__grid">
      <li v-for="item in items" :key="item.id">
        <RecordCard :item="item" />
      </li>
    </ul>

    <PaginationBar
      class="u-mt"
      :page="page"
      :page-size="pageSize"
      :total="total"
      @update:page="updatePage"
    />
  </section>
</template>

<style scoped>
.record-list__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
}

.record-list__title {
  font-size: var(--fs-xxl);
  font-weight: var(--fw-bold);
  line-height: var(--lh-tight);
}

.record-list__cta {
  white-space: nowrap;
}

.record-list__grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-4);
  margin-top: var(--sp-4);
}

@media (min-width: 768px) {
  .record-list__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 1120px) {
  .record-list__grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
