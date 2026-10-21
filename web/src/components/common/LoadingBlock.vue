<script setup lang="ts">
// 骨架屏样式一致（基线 §6）：任何页面不许「请求中白屏」
withDefaults(defineProps<{ rows?: number }>(), { rows: 3 })
</script>

<template>
  <div class="loading-block">
    <div v-for="row in rows" :key="row" class="loading-block__row"></div>
    <span class="loading-block__text">加载中…</span>
  </div>
</template>

<style scoped>
.loading-block {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-4);
  border-radius: var(--radius-md);
  background-color: var(--color-surface-muted);
}

.loading-block__row {
  height: var(--sp-6);
  border-radius: var(--radius-sm);
  background-color: var(--color-border);
}

.loading-block__text {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}
</style>
