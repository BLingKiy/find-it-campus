<script setup lang="ts">
// 类型 → 文案的唯一映射点（基线 §6）；颜色走 token，不写死色值
import type { RecordType } from '@/types/api'

const props = defineProps<{ type: RecordType }>()

const LABELS: Record<RecordType, string> = {
  LOST: '寻物',
  FOUND: '招领'
}
</script>

<template>
  <span class="type-tag" :class="`type-tag--${props.type.toLowerCase()}`">
    {{ LABELS[props.type] }}
  </span>
</template>

<style scoped>
.type-tag {
  display: inline-flex;
  align-items: center;
  padding: 0 var(--sp-2);
  border-radius: var(--radius-pill);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: var(--lh-tight);
  white-space: nowrap;
}

.type-tag--lost {
  color: var(--color-type-lost-fg);
  background-color: var(--color-type-lost-bg);
}

.type-tag--found {
  color: var(--color-type-found-fg);
  background-color: var(--color-type-found-bg);
}
</style>
