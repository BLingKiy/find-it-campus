<script setup lang="ts">
// 状态 → 颜色/文案的映射表集中一处（基线 §6）；除颜色外必须带文字
import type { RecordStatus } from '@/types/api'

const props = defineProps<{ status: RecordStatus }>()

const LABELS: Record<RecordStatus, string> = {
  PENDING: '待认领',
  CLAIMED: '已认领',
  CLOSED: '已关闭'
}
</script>

<template>
  <span class="status-tag" :class="`status-tag--${props.status.toLowerCase()}`">
    {{ LABELS[props.status] }}
  </span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  padding: 0 var(--sp-2);
  border-radius: var(--radius-pill);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: var(--lh-tight);
  white-space: nowrap;
}

.status-tag--pending {
  color: var(--color-status-pending-fg);
  background-color: var(--color-status-pending-bg);
}

.status-tag--claimed {
  color: var(--color-status-claimed-fg);
  background-color: var(--color-status-claimed-bg);
}

.status-tag--closed {
  color: var(--color-status-closed-fg);
  background-color: var(--color-status-closed-bg);
}
</style>
