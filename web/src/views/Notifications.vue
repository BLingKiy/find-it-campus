<script setup lang="ts">
// 通知（D2）：列表 + 未读数 + 逐条已读。
// 服务器写入时就已打码，这里仍只做纯文本渲染（不 v-html），通知正文不含手机号。
import { computed, ref, watch } from 'vue'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import LoadingBlock from '@/components/common/LoadingBlock.vue'
import PaginationBar from '@/components/filter/PaginationBar.vue'
import { useNotificationBadge } from '@/composables/useNotificationBadge'
import type { NotificationItem } from '@/types/api'

const PAGE_SIZE = 10

const NOTIFICATION_TYPES: Record<NotificationItem['type'], string> = {
  PAIR_MATCH: '匹配',
  PAIR_UNLOCK: '联系方式',
  SYSTEM: '系统'
}

const page = ref(1)
const items = ref<NotificationItem[]>([])
const total = ref(0)
const unreadCount = ref(0)
const loading = ref(false)
const error = ref<unknown>(null)
const markingId = ref<number | null>(null)
const markingAll = ref(false)
const notice = ref<string | null>(null)
const actionError = ref<unknown>(null)

const { setUnread } = useNotificationBadge()

const hasUnread = computed(() => unreadCount.value > 0)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await listNotifications({ page: page.value, pageSize: PAGE_SIZE })
    items.value = data.list
    total.value = data.total
    unreadCount.value = data.unreadCount
    setUnread(data.unreadCount)
  } catch (err) {
    error.value = err
  } finally {
    loading.value = false
  }
}

watch(page, () => void load(), { immediate: true })

async function markRead(item: NotificationItem): Promise<void> {
  if (item.read || markingId.value !== null || markingAll.value) return
  markingId.value = item.id
  actionError.value = null
  notice.value = null
  try {
    const result = await markNotificationRead(item.id)
    item.read = result.read
    unreadCount.value = result.unreadCount
    setUnread(result.unreadCount)
  } catch (err) {
    actionError.value = err
  } finally {
    markingId.value = null
  }
}

/** 全部标为已读：服务端只有单条接口，按页收集未读 id 后逐条 PATCH，最后用返回值同步红点 */
async function markAllRead(): Promise<void> {
  if (!hasUnread.value || markingAll.value) return
  markingAll.value = true
  actionError.value = null
  notice.value = null
  try {
    const ids: number[] = []
    let cursor = 1
    let hasNext = true
    while (hasNext && cursor <= 20) {
      const data = await listNotifications({ page: cursor, pageSize: 50 })
      for (const item of data.list) {
        if (!item.read) ids.push(item.id)
      }
      hasNext = data.hasNext
      cursor += 1
    }
    let latest = unreadCount.value
    for (const id of ids) {
      const result = await markNotificationRead(id)
      latest = result.unreadCount
    }
    unreadCount.value = latest
    setUnread(latest)
    notice.value = ids.length > 0 ? `已标记 ${ids.length} 条为已读` : '没有未读通知'
    await load()
  } catch (err) {
    actionError.value = err
  } finally {
    markingAll.value = false
  }
}
</script>

<template>
  <section class="notifications">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">通知</h1>
        <p class="page-head__desc">
          匹配与联系方式更新会在这里提醒你。通知只提示「有新的可能相关」，不包含手机号。
        </p>
      </div>
      <div class="page-head__actions">
        <button
          type="button"
          class="btn btn--ghost"
          :disabled="loading"
          data-testid="notifications-refresh"
          @click="load"
        >
          刷新
        </button>
        <button
          type="button"
          class="btn btn--primary"
          :disabled="!hasUnread || markingAll || loading"
          data-testid="notifications-mark-all"
          @click="markAllRead"
        >
          <span v-if="markingAll" class="btn__spinner" aria-hidden="true"></span>
          {{ markingAll ? '处理中…' : '全部标为已读' }}
        </button>
      </div>
    </header>

    <p class="notifications__summary" role="status" data-testid="notifications-unread">
      未读 <strong>{{ unreadCount }}</strong> 条 / 共 {{ total }} 条
    </p>

    <p
      v-if="notice"
      class="alert alert--success notifications__notice"
      role="status"
      data-testid="notifications-notice"
    >
      <span class="alert__title">已更新</span>
      <span class="alert__text">{{ notice }}</span>
    </p>

    <ErrorBanner v-if="actionError" :error="actionError" title="操作失败" class="notifications__error" />

    <LoadingBlock v-if="loading" variant="list" :rows="3" label="正在加载通知" />

    <ErrorBanner v-else-if="error" :error="error" retry-label="重试" @retry="load" />

    <template v-else>
      <EmptyState
        v-if="items.length === 0"
        title="暂无通知"
        description="新的匹配结果或对方公开联系方式后，这里会出现提醒。"
        :to="{ name: 'RecordList' }"
        action-label="去逛逛启事列表"
      />

      <template v-else>
        <ol class="notifications__list" data-testid="notifications-list">
          <li
            v-for="item in items"
            :key="item.id"
            class="notification"
            :class="{ 'notification--unread': !item.read }"
            data-testid="notification-item"
            :data-notification-id="item.id"
            :data-read="item.read ? 'true' : 'false'"
          >
            <div class="notification__head">
              <span class="badge badge--neutral">{{ NOTIFICATION_TYPES[item.type] }}</span>
              <span v-if="!item.read" class="badge badge--pending" data-testid="notification-unread-dot">
                未读
              </span>
              <span class="notification__time">{{ item.createdAt }}</span>
            </div>
            <h2 class="notification__title" data-testid="notification-title">{{ item.title }}</h2>
            <p class="notification__body" data-testid="notification-body">{{ item.body }}</p>
            <div class="notification__actions">
              <RouterLink
                v-if="item.pairId !== null"
                :to="{ name: 'MyRecords' }"
                class="btn btn--sm btn--ghost"
              >
                去我的启事查看
              </RouterLink>
              <button
                v-if="!item.read"
                type="button"
                class="btn btn--sm btn--ghost"
                :disabled="markingId !== null || markingAll"
                data-testid="notification-mark-read"
                @click="markRead(item)"
              >
                <span v-if="markingId === item.id" class="btn__spinner" aria-hidden="true"></span>
                {{ markingId === item.id ? '处理中…' : '标为已读' }}
              </button>
              <span v-else class="hint notification__read" data-testid="notification-read-state">
                已读
              </span>
            </div>
          </li>
        </ol>

        <PaginationBar
          class="notifications__pagination"
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
.notifications__summary {
  margin-bottom: var(--sp-3);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  font-variant-numeric: tabular-nums;
}

.notifications__summary strong {
  color: var(--color-primary-strong);
  font-size: var(--fs-md);
}

.notifications__notice,
.notifications__error {
  margin-bottom: var(--sp-3);
}

.notifications__list {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-3);
  min-width: 0;
}

.notification {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  min-width: 0;
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.notification--unread {
  border-left: 3px solid var(--color-primary);
  background-color: var(--color-primary-soft);
}

.notification__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}

.notification__time {
  margin-left: auto;
  color: var(--color-text-subtle);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
}

.notification__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  overflow-wrap: anywhere;
}

.notification__body {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  line-height: var(--lh-relaxed);
  overflow-wrap: anywhere;
}

.notification__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.notification__read {
  margin: 0;
}

.notifications__pagination {
  margin-top: var(--sp-4);
}

@media (max-width: 420px) {
  .notification {
    padding: var(--sp-3);
  }

  .notification__actions .btn {
    flex: 1 1 100%;
  }
}
</style>
