<script setup lang="ts">
// 骨架占位：匹配区三段状态（未开始 / 结果 / 不可用）由 4.1 填充（基线 §6）
defineProps<{ seedText: string; excludeRecordId?: number | null }>()
</script>

<template>
  <section class="match-panel">
    <h2 class="match-panel__title">帮我找找相关启事</h2>
    <p class="match-panel__hint">匹配区占位 · 30002/30003 只降级本区，详情主体照常可用</p>
  </section>
</template>

<style scoped>
.match-panel {
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.match-panel__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-medium);
}

.match-panel__hint {
  margin-top: var(--sp-2);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}
</style>
