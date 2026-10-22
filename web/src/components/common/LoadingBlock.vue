<script setup lang="ts">
// 加载态唯一形态（基线 §6 / ui-baseline §3.4）。任何页面不许"请求中白屏"。
// 骨架的形状与真实内容同构：卡片就摆卡片骨架，详情就摆详情骨架，
// 这样数据回来时不会因为高度突变产生 CLS（ui-baseline §5.2）。
withDefaults(
  defineProps<{
    /** 骨架块数量：列表页=卡片数，详情页=段落数 */
    rows?: number
    /** list（卡片网格）/ detail（大面板）/ inline（区内两三行） */
    variant?: 'list' | 'detail' | 'inline'
    label?: string
  }>(),
  { rows: 3, variant: 'list', label: '正在加载' }
)
</script>

<template>
  <div class="loading-block" :class="`loading-block--${variant}`" role="status" aria-busy="true">
    <span class="sr-only">{{ label }}，请稍候</span>

    <ul class="loading-block__cards" aria-hidden="true">
      <li v-for="row in rows" :key="row" class="loading-block__card">
        <div class="loading-block__row">
          <span class="skeleton loading-block__badge"></span>
          <span class="skeleton loading-block__badge loading-block__badge--short"></span>
        </div>
        <span class="skeleton skeleton__line skeleton__line--title"></span>
        <span class="skeleton skeleton__line"></span>
        <span class="skeleton skeleton__line skeleton__line--short"></span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.loading-block__cards {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-4);
}

.loading-block--inline .loading-block__cards {
  gap: var(--sp-3);
}

/* 与 RecordCard 同构：徽标行 + 标题 + 两行元信息 */
.loading-block__card {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.loading-block__row {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-1);
}

.loading-block__badge {
  width: 44px;
  height: 22px;
  border-radius: var(--radius-pill);
}

.loading-block__badge--short {
  width: 52px;
}

/* 固定高度避免数据回来时跳动（CLS 预算 0.1） */
.loading-block__card .skeleton__line {
  height: 12px;
}

.loading-block__card .skeleton__line--title {
  height: 18px;
  width: 58%;
}

@media (min-width: 768px) {
  .loading-block--list .loading-block__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 1120px) {
  .loading-block--list .loading-block__cards {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

/* 详情骨架：一整块，不做网格 */
.loading-block--detail .loading-block__cards {
  grid-template-columns: 1fr;
}

.loading-block--inline .loading-block__card {
  padding: var(--sp-3);
}
</style>
