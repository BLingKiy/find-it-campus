<script setup lang="ts">
import type { RecordListItem } from '@/types/api'
import RecordStatusTag from './RecordStatusTag.vue'
import RecordTypeTag from './RecordTypeTag.vue'

defineProps<{ item: RecordListItem }>()
</script>

<template>
  <!-- 可点击卡片用 RouterLink 而非 div @click（基线 §7 无障碍基线） -->
  <RouterLink class="record-card" :to="{ name: 'RecordDetail', params: { id: item.id } }">
    <div class="record-card__tags">
      <RecordTypeTag :type="item.type" />
      <RecordStatusTag :status="item.status" />
    </div>
    <h3 class="record-card__title">{{ item.title }}</h3>
    <p class="record-card__meta">{{ item.categoryName ?? '未分类' }} · {{ item.location }}</p>
    <p class="record-card__time">发生：{{ item.occurredAt }}</p>
  </RouterLink>
</template>

<style scoped>
.record-card {
  display: block;
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
  box-shadow: var(--shadow-sm);
  color: inherit;
  transition: box-shadow var(--dur-fast) ease;
}

.record-card:hover {
  text-decoration: none;
  box-shadow: var(--shadow-md);
}

.record-card__tags {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.record-card__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-medium);
  line-height: var(--lh-tight);
}

.record-card__meta {
  margin-top: var(--sp-2);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.record-card__time {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}
</style>
