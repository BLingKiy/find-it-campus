<script setup lang="ts">
// 分数口径统一：只用后端给的 scorePercent，展示为「相似度 N%」，前端不二次加工（契约 §8.4）
import RecordStatusTag from '@/components/record/RecordStatusTag.vue'
import RecordTypeTag from '@/components/record/RecordTypeTag.vue'
import type { MatchItem } from '@/types/api'

defineProps<{ item: MatchItem }>()
</script>

<template>
  <RouterLink class="match-item-card" :to="{ name: 'RecordDetail', params: { id: item.recordId } }">
    <div class="match-item-card__head">
      <span class="match-item-card__score">相似度 {{ item.scorePercent }}%</span>
      <RecordTypeTag :type="item.type" />
      <RecordStatusTag :status="item.status" />
    </div>
    <h3 class="match-item-card__title">{{ item.title }}</h3>
    <p class="match-item-card__meta">
      {{ item.categoryName ?? '未分类' }} · {{ item.location }} · 发生：{{ item.occurredAt }}
    </p>
    <p class="match-item-card__reason">{{ item.reason }}</p>
  </RouterLink>
</template>

<style scoped>
.match-item-card {
  display: block;
  padding: var(--sp-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface);
  color: inherit;
}

.match-item-card:hover {
  text-decoration: none;
  border-color: var(--color-primary);
}

.match-item-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.match-item-card__score {
  color: var(--color-primary-strong);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.match-item-card__title {
  margin-top: var(--sp-2);
  font-size: var(--fs-md);
  font-weight: var(--fw-medium);
  line-height: var(--lh-tight);
}

.match-item-card__meta {
  margin-top: var(--sp-1);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.match-item-card__reason {
  margin-top: var(--sp-2);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}
</style>
