<script setup lang="ts">
// 页码窗口算法（当前页 ±2）集中在这里，页面不各写一套（基线 §6）
import { computed } from 'vue'

const props = defineProps<{ page: number; pageSize: number; total: number }>()
const emit = defineEmits<{ 'update:page': [page: number] }>()

const WINDOW = 2

const totalPages = computed(() => Math.ceil(props.total / props.pageSize) || 1)

/** 省略号占位用 null 表示，模板据此渲染 … */
const pages = computed<(number | null)[]>(() => {
  const last = totalPages.value
  const start = Math.max(1, props.page - WINDOW)
  const end = Math.min(last, props.page + WINDOW)
  const result: (number | null)[] = []
  if (start > 1) {
    result.push(1)
    if (start > 2) result.push(null)
  }
  for (let num = start; num <= end; num++) result.push(num)
  if (end < last) {
    if (end < last - 1) result.push(null)
    result.push(last)
  }
  return result
})

function go(target: number): void {
  const next = Math.min(Math.max(target, 1), totalPages.value)
  if (next === props.page) return
  emit('update:page', next)
}
</script>

<template>
  <nav v-if="total > 0" class="pagination-bar" aria-label="列表分页">
    <button type="button" class="pagination-bar__step" :disabled="page <= 1" @click="go(page - 1)">
      上一页
    </button>

    <template v-for="(item, index) in pages">
      <span v-if="item === null" :key="`gap-${index}`" class="pagination-bar__gap">…</span>
      <button
        v-else
        :key="item"
        type="button"
        class="pagination-bar__page"
        :class="{ 'is-active': item === page }"
        :aria-current="item === page ? 'page' : undefined"
        @click="go(item)"
      >
        {{ item }}
      </button>
    </template>

    <button
      type="button"
      class="pagination-bar__step"
      :disabled="page >= totalPages"
      @click="go(page + 1)"
    >
      下一页
    </button>

    <span class="pagination-bar__total">共 {{ total }} 条 / {{ totalPages }} 页</span>
  </nav>
</template>

<style scoped>
.pagination-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.pagination-bar__step,
.pagination-bar__page {
  padding: var(--sp-1) var(--sp-3);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface);
  font-size: var(--fs-sm);
}

.pagination-bar__step:disabled {
  color: var(--color-text-muted);
  border-color: var(--color-border);
  cursor: not-allowed;
}

.pagination-bar__page.is-active {
  border-color: var(--color-primary);
  background-color: var(--color-primary-soft);
  color: var(--color-primary-strong);
  font-weight: var(--fw-medium);
}

.pagination-bar__gap {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.pagination-bar__total {
  margin-left: auto;
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}
</style>
