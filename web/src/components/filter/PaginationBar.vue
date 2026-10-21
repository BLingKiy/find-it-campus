<script setup lang="ts">
// 骨架占位：页码窗口算法（当前页 ±2）由 4.1 填充（基线 §6）
defineProps<{ page: number; pageSize: number; total: number }>()
defineEmits<{ 'update:page': [page: number] }>()
</script>

<template>
  <div class="pagination-bar">分页占位</div>
</template>

<style scoped>
.pagination-bar {
  padding: var(--sp-2) var(--sp-4);
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}
</style>
