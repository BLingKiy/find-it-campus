<script setup lang="ts">
// 我的启事（D2）：所有者私有视图，三态页签都展示本人数据。
// 数据只来自 GET /api/my/records（pairs[] 由 E1b 后端真实返回），页面不造数、不改后端。
// 「分析中」时每 5 秒自动刷新；出分后展示「N 条可能相关」，N 用 pairCount（t_pair_score 行数口径）。
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listMyRecords } from '@/api/myRecords'
import MyRecordCard from '@/components/record/MyRecordCard.vue'
import MyRecordSummary from '@/components/record/MyRecordSummary.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import LoadingBlock from '@/components/common/LoadingBlock.vue'
import PaginationBar from '@/components/filter/PaginationBar.vue'
import type { MyRecordItem, RecordStatus } from '@/types/api'

type TabKey = RecordStatus
type QueryTab = 'pending' | 'completed' | 'closed'

const PAGE_SIZE = 5
const TAB_TO_QUERY: Record<TabKey, QueryTab> = {
  PENDING: 'pending',
  COMPLETED: 'completed',
  CLOSED: 'closed'
}
const QUERY_TO_TAB: Record<string, TabKey> = {
  pending: 'PENDING',
  completed: 'COMPLETED',
  closed: 'CLOSED'
}

const route = useRoute()
const router = useRouter()

function initialTab(): TabKey {
  if (route.query.updated === 'completed') return 'COMPLETED'
  if (route.query.updated === 'closed') return 'CLOSED'
  return QUERY_TO_TAB[String(route.query.tab ?? '')] ?? 'PENDING'
}

const activeTab = ref<TabKey>(initialTab())
const page = ref(1)
const items = ref<MyRecordItem[]>([])
const total = ref(0)
const loading = ref(false)
const refreshing = ref(false)
const error = ref<unknown>(null)
const notice = ref<{ kind: 'success' | 'info'; title: string; text: string } | null>(null)

const tabs: { key: TabKey; label: string; testId: string }[] = [
  { key: 'PENDING', label: '进行中', testId: 'my-tab-pending' },
  { key: 'COMPLETED', label: '已完成', testId: 'my-tab-completed' },
  { key: 'CLOSED', label: '已关闭', testId: 'my-tab-closed' }
]

const analyzingCount = computed(
  () =>
    items.value.filter(
      (item) => item.pairState === 'PENDING' || item.pairState === 'SCORING'
    ).length
)

const emptyCopy = computed(() => {
  if (activeTab.value === 'COMPLETED') {
    return { title: '还没有已完成的启事', description: '在「进行中」里，双方都确认后可标记完成。' }
  }
  if (activeTab.value === 'CLOSED') {
    return { title: '还没有已关闭的启事', description: '已关闭的启事不再公开展示，也不再参与匹配。' }
  }
  return { title: '还没有进行中的启事', description: '发布一条寻物或招领，匹配会自动开始。' }
})

/** 从详情页关闭/完成后带回的一次性提示（刷新后 query 还在，不重复请求数据） */
function noticeFromQuery(): void {
  if (route.query.updated === 'completed') {
    notice.value = {
      kind: 'success',
      title: '已标记完成',
      text: '这条启事已从公共列表隐藏；保留期内仍会参与匹配。'
    }
  } else if (route.query.updated === 'closed') {
    notice.value = {
      kind: 'success',
      title: '已关闭',
      text: '这条启事不再公开展示，也不再参与匹配。'
    }
  } else if (route.query.unavailable === '1') {
    notice.value = {
      kind: 'info',
      title: '该启事已关闭或不可查看',
      text: '已完成 / 已关闭的启事不提供公开详情，可在本页查看摘要。'
    }
  }
}
noticeFromQuery()

async function load(silent = false): Promise<void> {
  if (loading.value || refreshing.value) return
  if (silent) refreshing.value = true
  else loading.value = true
  error.value = null
  try {
    const data = await listMyRecords({
      status: activeTab.value,
      page: page.value,
      pageSize: PAGE_SIZE
    })
    items.value = data.list
    total.value = data.total
    // 删除/状态变化后当前页可能越界，回到最后一页再拉一次
    if (data.totalPages > 0 && page.value > data.totalPages) {
      page.value = data.totalPages
      return
    }
  } catch (err) {
    error.value = err
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

watch([activeTab, page], () => void load(), { immediate: true })

// 外部（浏览器前进/后退或详情页回跳）改 query 时同步页签
watch(
  () => route.query.tab,
  (raw) => {
    const next = QUERY_TO_TAB[String(raw ?? '')] ?? 'PENDING'
    if (next === activeTab.value) return
    activeTab.value = next
    page.value = 1
  }
)

function goTab(tab: TabKey): void {
  if (activeTab.value === tab) return
  activeTab.value = tab
  page.value = 1
  notice.value = null
  void router.replace({
    name: 'MyRecords',
    query: tab === 'PENDING' ? {} : { tab: TAB_TO_QUERY[tab] }
  })
}

function handleChanged(payload: { id: number; status: RecordStatus }): void {
  if (payload.status === 'COMPLETED') {
    goTab('COMPLETED')
    notice.value = {
      kind: 'success',
      title: '已标记完成',
      text: `启事 #${payload.id} 已完成，已移到「已完成」页签；保留期内仍参与匹配。`
    }
  } else if (payload.status === 'CLOSED') {
    goTab('CLOSED')
    notice.value = {
      kind: 'success',
      title: '已关闭',
      text: `启事 #${payload.id} 已关闭，不再公开展示，也不再参与匹配。`
    }
  }
}

/** pair 确认后重拉：同步 hasMatchedPair / matchedPairCount，「标记完成」按钮据此点亮 */
function handlePairRefresh(): void {
  void load(true)
}

// 「分析中」自动刷新：仅在进行中页签且确实有 PENDING/SCORING 时轮询，避免无意义请求
let pollTimer: ReturnType<typeof setInterval> | null = null
pollTimer = setInterval(() => {
  if (activeTab.value !== 'PENDING') return
  if (analyzingCount.value === 0) return
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
  void load(true)
}, 5000)

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
})
</script>

<template>
  <section class="my-records">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">我的启事</h1>
        <p class="page-head__desc">
          管理你发布过的启事：进行中可编辑 / 标记完成 / 关闭；已完成与已关闭只读。配对逐条独立处理。
        </p>
      </div>
      <div class="page-head__actions">
        <button
          type="button"
          class="btn btn--ghost"
          :disabled="loading || refreshing"
          data-testid="my-refresh"
          @click="load()"
        >
          <span v-if="refreshing" class="btn__spinner" aria-hidden="true"></span>
          {{ refreshing ? '刷新中…' : '刷新' }}
        </button>
        <RouterLink :to="{ name: 'RecordCreate' }" class="btn btn--primary">
          + 发布启事
        </RouterLink>
      </div>
    </header>

    <p
      v-if="notice"
      class="alert my-records__notice"
      :class="`alert--${notice.kind}`"
      role="status"
      data-testid="my-records-notice"
    >
      <span class="alert__title">{{ notice.title }}</span>
      <span class="alert__text">{{ notice.text }}</span>
    </p>

    <div class="my-records__tabs" role="tablist" aria-label="我的启事状态">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        role="tab"
        class="my-records__tab"
        :class="{ 'is-active': activeTab === tab.key }"
        :aria-selected="activeTab === tab.key"
        :data-testid="tab.testId"
        @click="goTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <p
      v-if="activeTab === 'PENDING' && analyzingCount > 0"
      class="hint my-records__poll"
      data-testid="my-records-polling"
      role="status"
    >
      有 {{ analyzingCount }} 条正在匹配分析，页面每 5 秒自动刷新。
    </p>

    <LoadingBlock v-if="loading" variant="list" :rows="3" label="正在加载我的启事" />

    <ErrorBanner
      v-else-if="error"
      :error="error"
      retry-label="重试"
      @retry="load()"
    />

    <template v-else>
      <EmptyState
        v-if="items.length === 0"
        :title="emptyCopy.title"
        :description="emptyCopy.description"
        :to="activeTab === 'PENDING' ? { name: 'RecordCreate' } : undefined"
        :action-label="activeTab === 'PENDING' ? '去发布' : undefined"
      />

      <template v-else>
        <ol
          v-if="activeTab === 'PENDING'"
          class="my-records__list"
          data-testid="my-records-list"
        >
          <li v-for="item in items" :key="item.id">
            <MyRecordCard
              :record="item"
              @changed="handleChanged"
              @refresh="handlePairRefresh"
            />
          </li>
        </ol>

        <ol
          v-else
          class="my-records__list my-records__list--summary"
          data-testid="my-records-summary-list"
        >
          <li v-for="item in items" :key="item.id">
            <MyRecordSummary :record="item" />
          </li>
        </ol>

        <PaginationBar
          class="my-records__pagination"
          :page="page"
          :page-size="PAGE_SIZE"
          :total="total"
          @update:page="page = $event"
        />
      </template>
    </template>
  </section>
</template>

<style scoped>
.my-records__notice {
  margin-bottom: var(--sp-4);
}

.my-records__notice + .my-records__tabs {
  margin-top: calc(-1 * var(--sp-2));
}

.my-records__tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-1);
  margin-bottom: var(--sp-4);
  padding: 3px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface-muted);
}

.my-records__tab {
  min-height: var(--control-height-sm);
  padding: 0 var(--sp-4);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background-color: transparent;
  color: var(--color-text-muted);
  font: inherit;
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  cursor: pointer;
  white-space: nowrap;
}

.my-records__tab:hover {
  background-color: var(--color-surface);
  color: var(--color-text);
}

.my-records__tab.is-active {
  border-color: var(--color-primary);
  background-color: var(--color-surface);
  color: var(--color-primary-strong);
  font-weight: var(--fw-semibold);
}

.my-records__poll {
  margin-bottom: var(--sp-3);
}

.my-records__list {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-4);
  min-width: 0;
}

.my-records__pagination {
  margin-top: var(--sp-4);
}

@media (max-width: 420px) {
  .my-records__tabs {
    flex-wrap: nowrap;
  }

  .my-records__tab {
    flex: 1 1 0;
    min-width: 0;
    padding: 0 var(--sp-2);
    text-align: center;
  }
}
</style>
