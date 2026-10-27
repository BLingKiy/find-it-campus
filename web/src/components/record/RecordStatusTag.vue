<script setup lang="ts">
// 状态 → 颜色/文案的映射表集中一处（基线 §6）。
// 视觉走 components.css 的 .badge（文字 + 色点 + 底色三通道），不只靠颜色分状态。
import type { RecordStatus } from '@/types/api'

const props = defineProps<{ status: RecordStatus }>()

const LABELS: Record<RecordStatus, string> = {
  PENDING: '进行中',
  COMPLETED: '已完成',
  CLOSED: '已关闭'
}
</script>

<template>
  <span class="badge badge--dot" :class="`badge--${props.status.toLowerCase()}`">
    {{ LABELS[props.status] }}
  </span>
</template>
