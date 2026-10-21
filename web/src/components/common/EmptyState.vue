<script setup lang="ts">
// 所有空态一致（基线 §6）；骨架阶段只有展示，无交互
defineProps<{ title: string; description?: string; actionText?: string }>()
</script>

<template>
  <div class="empty-state">
    <p class="empty-state__title">{{ title }}</p>
    <p v-if="description" class="empty-state__desc">{{ description }}</p>
    <p v-if="actionText" class="empty-state__action">{{ actionText }}</p>
  </div>
</template>

<style scoped>
.empty-state {
  padding: var(--sp-8) var(--sp-4);
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
  text-align: center;
}

.empty-state__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-medium);
}

.empty-state__desc,
.empty-state__action {
  margin-top: var(--sp-2);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}
</style>
