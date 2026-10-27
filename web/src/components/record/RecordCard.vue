<script setup lang="ts">
// 列表卡片（基线 §6）：卡片视觉只改一次，列表页/空态都不各写一套。
// 4.2 加的左侧类型色条：寻物/招领在密集列表里能一眼分类，且不依赖表情或图标。
// C2：本人行多一个管理入口（编辑 / 关闭）。卡片不再是整块 RouterLink ——
// 链接里套按钮是无效 HTML 且键盘无法分别聚焦，改成「标题链接拉伸覆盖卡片 + 操作区更高层级」。
import type { RecordListItem, RecordStatus } from '@/types/api'
import OwnRecordActions from './OwnRecordActions.vue'
import RecordStatusTag from './RecordStatusTag.vue'
import RecordTypeTag from './RecordTypeTag.vue'

defineProps<{ item: RecordListItem }>()

const emit = defineEmits<{
  /** 关闭成功：父组件据此刷新当前列表（关闭后该行不再属于公共列表） */
  changed: [payload: { id: number; status: RecordStatus }]
}>()
</script>

<template>
  <article
    class="record-card"
    :class="[`record-card--${item.type.toLowerCase()}`, { 'record-card--mine': item.isMine }]"
  >
    <div class="record-card__tags">
      <RecordTypeTag :type="item.type" />
      <RecordStatusTag :status="item.status" />
      <span v-if="item.isMine" class="record-card__mine" data-testid="card-mine-badge">我的启事</span>
      <span class="record-card__id">#{{ item.id }}</span>
    </div>

    <h2 class="record-card__title">
      <RouterLink class="record-card__link" :to="{ name: 'RecordDetail', params: { id: item.id } }">
        {{ item.title }}
      </RouterLink>
    </h2>

    <p class="record-card__meta">
      <span>{{ item.categoryName ?? '未分类' }}</span>
      <span class="record-card__sep" aria-hidden="true">·</span>
      <span>{{ item.location }}</span>
    </p>

    <p class="record-card__time">发生 {{ item.occurredAt }}</p>

    <div v-if="item.isMine" class="record-card__manage">
      <OwnRecordActions
        variant="card"
        :record-id="item.id"
        :status="item.status"
        :is-mine="item.isMine"
        @changed="emit('changed', $event)"
      />
    </div>
  </article>
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
}

.record-card:active {
  transform: translateY(0);
}

.record-card:focus-within {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}

.record-card__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.record-card__mine {
  padding: 0 var(--sp-2);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-soft);
  color: var(--color-primary-strong);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: 22px;
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

/* 拉伸链接：一张卡片只有这一个可点区域，管理按钮在它之上 */
.record-card__link {
  color: inherit;
}

.record-card__link::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
}

.record-card__link:hover {
  text-decoration: none;
}

.record-card__link:focus-visible {
  outline: none;
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

/* 操作区必须压在拉伸链接之上，否则点「关闭」会被链接截走 */
.record-card__manage {
  position: relative;
  z-index: 1;
}
</style>
