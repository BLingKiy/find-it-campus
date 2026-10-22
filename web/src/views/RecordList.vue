<script setup lang="ts">
// 列表页 = 首页（基线 §2）。数据只经由 api/records.ts 的 listRecords，页面里不出现 fetch
import { computed, onMounted } from 'vue'
import { listRecords } from '@/api/records'
import RecordCard from '@/components/record/RecordCard.vue'
import RecordFilterBar from '@/components/filter/RecordFilterBar.vue'
import PaginationBar from '@/components/filter/PaginationBar.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import LoadingBlock from '@/components/common/LoadingBlock.vue'
import { useRequest } from '@/composables/useRequest'
import type { RecordQuery } from '@/types/api'

const { data, error, loading, run } = useRequest(listRecords, { immediate: false })

const items = computed(() => data.value?.list ?? [])
const total = computed(() => data.value?.total ?? 0)
const page = computed(() => data.value?.page ?? 1)
const pageSize = computed(() => data.value?.pageSize ?? 10)

onMounted(() => {
  const query: RecordQuery = { page: 1, pageSize: 10 }
  void run(query)
})
</script>

<template>
  <section class="record-list">
    <header class="record-list__head">
      <h1 class="record-list__title">启事列表</h1>
      <RouterLink :to="{ name: 'RecordCreate' }" class="record-list__cta">+ 发布启事</RouterLink>
    </header>

    <RecordFilterBar />

    <ErrorBanner v-if="error" :error="error" class="u-mt" />
    <LoadingBlock v-if="loading" :rows="3" class="u-mt" />
    <EmptyState
      v-else-if="!loading && items.length === 0"
      class="u-mt"
      title="暂无符合条件的启事"
      description="换个筛选条件，或发布一条新的启事"
      action-text="清空筛选试试"
    />

    <ul v-else class="record-list__grid">
      <li v-for="item in items" :key="item.id">
        <RecordCard :item="item" />
      </li>
    </ul>

    <PaginationBar
      v-if="items.length > 0"
      class="u-mt"
      :page="page"
      :page-size="pageSize"
      :total="total"
    />
  </section>
</template>

<style scoped>
.record-list__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sp-4);
}

.record-list__title {
  font-size: var(--fs-xxl);
  font-weight: var(--fw-bold);
  line-height: var(--lh-tight);
}

.record-list__cta {
  padding: var(--sp-2) var(--sp-4);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary);
  color: var(--color-bg);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.record-list__cta:hover {
  background-color: var(--color-primary-strong);
  text-decoration: none;
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
