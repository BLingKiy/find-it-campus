<script setup lang="ts">
// 列表卡片（基线 §6）：卡片视觉只改一次，列表页/空态都不各写一套。
// 可点击卡片用 RouterLink 而非 div @click（基线 §7 无障碍基线）。
// 4.2 加的左侧类型色条：寻物/招领在密集列表里能一眼分类，且不依赖表情或图标。
import type { RecordListItem } from '@/types/api'
import RecordStatusTag from './RecordStatusTag.vue'
import RecordTypeTag from './RecordTypeTag.vue'

defineProps<{ item: RecordListItem }>()
</script>

<template>
  <RouterLink
    class="record-card"
    :class="`record-card--${item.type.toLowerCase()}`"
    :to="{ name: 'RecordDetail', params: { id: item.id } }"
  >
    <div class="record-card__tags">
      <RecordTypeTag :type="item.type" />
      <RecordStatusTag :status="item.status" />
      <span class="record-card__id">#{{ item.id }}</span>
    </div>

    <h2 class="record-card__title">{{ item.title }}</h2>

    <p class="record-card__meta">
      <span>{{ item.categoryName ?? '未分类' }}</span>
      <span class="record-card__sep" aria-hidden="true">·</span>
      <span>{{ item.location }}</span>
    </p>

    <p class="record-card__time">发生 {{ item.occurredAt }}</p>
  </RouterLink>
</template>

<style scoped>
.record-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  height: 100%;
  padding: var(--sp-4);
  /* 左色条用 inset box-shadow 实现，避免 border 改变内宽、也避免 1px 抖动 */
  box-shadow: inset 3px 0 0 var(--card-accent), var(--shadow-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
  color: inherit;
  transition:
    transform var(--dur-base) var(--ease-out),
    box-shadow var(--dur-base) var(--ease-out),
    border-color var(--dur-base) var(--ease-out);
}

.record-card--lost {
  --card-accent: var(--color-type-lost-fg);
}

.record-card--found {
  --card-accent: var(--color-type-found-fg);
}

/* 只动 transform / box-shadow，不触发布局重排（ui-baseline §2.5） */
.record-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-border-strong);
  box-shadow: inset 3px 0 0 var(--card-accent), var(--shadow-md);
  text-decoration: none;
}

.record-card:active {
  transform: translateY(0);
}

.record-card__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.record-card__id {
  margin-left: auto;
  color: var(--color-text-subtle);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.record-card__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
}

.record-card__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--sp-1);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.record-card__sep {
  color: var(--color-text-subtle);
}

.record-card__time {
  margin-top: auto;
  color: var(--color-text-subtle);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
}
</style>
