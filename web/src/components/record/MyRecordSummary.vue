<script setup lang="ts">
// 「已完成 / 已关闭」页签的只读摘要卡（D2 vF）：
// - COMPLETED/CLOSED 的所有者详情按契约是 404，所以这里不提供「查看详情」入口；
// - 只展示摘要，已完成额外标 completed_at、匹配对数与保留期。
import { computed } from 'vue'
import RecordStatusTag from '@/components/record/RecordStatusTag.vue'
import RecordTypeTag from '@/components/record/RecordTypeTag.vue'
import { COMPLETED_RETENTION_DAYS } from '@/config/retention'
import type { MyRecordItem } from '@/types/api'

const props = defineProps<{ record: MyRecordItem }>()

function parseDateTime(value: string): number | null {
  // 契约时间格式 yyyy-MM-dd HH:mm:ss（本地时区），Safari 不接受空格分隔，换成 T
  const parsed = new Date(value.replace(' ', 'T')).getTime()
  return Number.isFinite(parsed) ? parsed : null
}

const expired = computed(() => {
  if (props.record.status !== 'COMPLETED' || !props.record.completedAt) return false
  const completedAt = parseDateTime(props.record.completedAt)
  if (completedAt === null) return false
  return Date.now() >= completedAt + COMPLETED_RETENTION_DAYS * 24 * 60 * 60 * 1000
})
</script>

<template>
  <article
    class="my-summary"
    data-testid="my-record-summary"
    :data-record-id="record.id"
    :data-record-status="record.status"
  >
    <header class="my-summary__head">
      <RecordTypeTag :type="record.type" />
      <RecordStatusTag :status="record.status" />
      <span class="my-summary__id">#{{ record.id }}</span>
    </header>

    <h3 class="my-summary__title">{{ record.title }}</h3>

    <dl class="my-summary__facts">
      <div>
        <dt>分类</dt>
        <dd>{{ record.categoryName ?? '未分类' }}</dd>
      </div>
      <div>
        <dt>地点</dt>
        <dd>{{ record.location }}</dd>
      </div>
      <div v-if="record.status === 'COMPLETED'">
        <dt>完成时间</dt>
        <dd data-testid="my-summary-completed-at">{{ record.completedAt ?? '—' }}</dd>
      </div>
      <div v-if="record.status === 'COMPLETED'">
        <dt>匹配对数</dt>
        <dd data-testid="my-summary-matched-count">{{ record.matchedPairCount }}</dd>
      </div>
      <div v-if="record.status === 'CLOSED'">
        <dt>发生时间</dt>
        <dd>{{ record.occurredAt }}</dd>
      </div>
    </dl>

    <p
      v-if="record.status === 'COMPLETED'"
      class="hint my-summary__hint"
      data-testid="my-summary-readonly-hint"
    >
      已完成的启事不再提供详情入口；保留期内仍会参与匹配。
    </p>
    <p v-else class="hint my-summary__hint" data-testid="my-summary-readonly-hint">
      已关闭的启事只读：不再公开展示，也不再参与匹配。
    </p>

    <p
      v-if="expired"
      class="badge badge--warning my-summary__expired"
      data-testid="my-summary-expired"
    >
      已过保留期，不再进入匹配
    </p>
  </article>
</template>

<style scoped>
.my-summary {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface-muted);
}

.my-summary__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}

.my-summary__id {
  margin-left: auto;
  color: var(--color-text-subtle);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.my-summary__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  overflow-wrap: anywhere;
}

.my-summary__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-2) var(--sp-4);
  min-width: 0;
}

.my-summary__facts dt {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.my-summary__facts dd {
  margin-top: 2px;
  font-size: var(--fs-sm);
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

.my-summary__hint {
  margin: 0;
}

.my-summary__expired {
  align-self: flex-start;
  white-space: normal;
}

@media (max-width: 420px) {
  .my-summary {
    padding: var(--sp-3);
  }

  .my-summary__facts {
    grid-template-columns: 1fr;
  }
}
</style>
