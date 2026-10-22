<script setup lang="ts">
// 类型 → 文案的唯一映射点（基线 §6）。视觉与状态标签共用 .badge 原始类，保证同类元素一致。
import type { RecordType } from '@/types/api'

const props = defineProps<{ type: RecordType }>()

const LABELS: Record<RecordType, string> = {
  LOST: '寻物',
  FOUND: '招领'
}
</script>

<template>
  <span class="badge badge--dot" :class="`badge--${props.type.toLowerCase()}`">
    {{ LABELS[props.type] }}
  </span>
</template>
