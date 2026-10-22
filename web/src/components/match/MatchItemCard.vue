<script setup lang="ts">
// /api/match 结果的单条候选（ui-baseline §4.5）。
// 分数口径统一：只用后端给的 scorePercent 展示为「相似度 N%」，前端不二次加工（契约 §8.4）；
// 分档（high/mid/low）只影响进度条配色，不参与排序、不改分值。
import { computed } from 'vue'
import RecordStatusTag from '@/components/record/RecordStatusTag.vue'
import RecordTypeTag from '@/components/record/RecordTypeTag.vue'
import type { MatchItem } from '@/types/api'

const props = defineProps<{ item: MatchItem; rank: number }>()

/** 展示分档阈值：≥70 高、≥40 中、其余低（只影响颜色） */
const band = computed(() => {
  if (props.item.scorePercent >= 70) return 'high'
  if (props.item.scorePercent >= 40) return 'mid'
  return 'low'
})
</script>

<template>
  <RouterLink
    class="match-item"
    :to="{ name: 'RecordDetail', params: { id: item.recordId } }"
  >
    <div class="match-item__head">
      <span class="match-item__rank" aria-hidden="true">{{ rank }}</span>
      <RecordTypeTag :type="item.type" />
      <RecordStatusTag :status="item.status" />
      <span class="match-item__id">#{{ item.recordId }}</span>
    </div>

    <h3 class="match-item__title">{{ item.title }}</h3>

    <div class="match-item__score">
      <span class="match-item__score-label">相似度</span>
      <span class="score" :data-band="band">
        <span class="score__bar" aria-hidden="true">
          <span class="score__fill" :style="{ '--score': `${item.scorePercent}%` }"></span>
        </span>
        <span class="score__value">{{ item.scorePercent }}%</span>
      </span>
    </div>

    <p class="match-item__meta">
      {{ item.categoryName ?? '未分类' }} · {{ item.location }} · 发生 {{ item.occurredAt }}
    </p>

    <p class="match-item__reason">{{ item.reason }}</p>
  </RouterLink>
</template>

<style scoped>
.match-item {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface);
  color: inherit;
  transition:
    border-color var(--dur-fast) var(--ease-standard),
    background-color var(--dur-fast) var(--ease-standard);
}

.match-item:hover {
  border-color: var(--color-primary);
  background-color: var(--color-primary-softer);
  text-decoration: none;
}

.match-item__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

/* 排序可见：名次是真实 DOM 文本，读屏按列表顺序读，色块只是视觉锚点 */
.match-item__rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: var(--radius-xs);
  background-color: var(--color-surface-muted);
  color: var(--color-text-muted);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  font-variant-numeric: tabular-nums;
}

.match-item__id {
  margin-left: auto;
  color: var(--color-text-subtle);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.match-item__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
}

.match-item__score {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.match-item__score-label {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.match-item__score .score {
  flex: 1 1 auto;
  max-width: 320px;
}

.match-item__meta {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.match-item__reason {
  padding-left: var(--sp-2);
  border-left: 2px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}
</style>
