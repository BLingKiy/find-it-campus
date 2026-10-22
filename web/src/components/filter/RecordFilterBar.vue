<script setup lang="ts">
// 筛选表单 ↔ URL query（基线 §6）：v-model 进来的就是列表页那一份 state，
// 因此「筛选条件写进 URL → 详情后退能回到原结果」不需要任何额外代码。
import { onMounted, ref } from 'vue'
import { loadCategories } from '@/api/categoryCache'
import type { CategoryItem } from '@/types/api'
import type { ListFilterState } from '@/types/domain'

const props = defineProps<{ modelValue: ListFilterState }>()
const emit = defineEmits<{ 'update:modelValue': [value: ListFilterState] }>()

const TYPE_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'LOST', label: '寻物' },
  { value: 'FOUND', label: '招领' }
]

const STATUS_OPTIONS = [
  { value: 'PENDING', label: '待认领' },
  { value: 'CLAIMED', label: '已认领' },
  { value: 'CLOSED', label: '已关闭' },
  // 服务端认 ALL，URL 里保留 status=ALL，刷新/分享后筛选态不丢
  { value: 'ALL', label: '全部状态' }
]

const categories = ref<CategoryItem[]>([])
const categoryHint = ref('')

function patch(next: Partial<ListFilterState>): void {
  emit('update:modelValue', { ...props.modelValue, ...next })
}

function reset(): void {
  emit('update:modelValue', { type: '', status: 'PENDING', categoryId: '', keyword: '' })
}

onMounted(async () => {
  try {
    categories.value = await loadCategories()
  } catch {
    // 字典拿不到只降级下拉本身，其余筛选条件照常可用
    categoryHint.value = '分类加载失败，可先按其他条件筛选'
  }
})
</script>

<template>
  <!-- 筛选即时生效，回车不应触发表单默认提交导致整页刷新 -->
  <form class="filter-bar" role="search" @submit.prevent>
    <div class="filter-bar__row">
      <div class="filter-bar__field">
        <label class="filter-bar__label" for="filter-type">类型</label>
        <select
          id="filter-type"
          class="filter-bar__control"
          :value="modelValue.type"
          @change="patch({ type: ($event.target as HTMLSelectElement).value as ListFilterState['type'] })"
        >
          <option v-for="option in TYPE_OPTIONS" :key="option.label" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </div>

      <div class="filter-bar__field">
        <label class="filter-bar__label" for="filter-status">状态</label>
        <select
          id="filter-status"
          class="filter-bar__control"
          :value="modelValue.status"
          @change="patch({ status: ($event.target as HTMLSelectElement).value as ListFilterState['status'] })"
        >
          <option v-for="option in STATUS_OPTIONS" :key="option.label" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </div>

      <div class="filter-bar__field">
        <label class="filter-bar__label" for="filter-category">分类</label>
        <select
          id="filter-category"
          class="filter-bar__control"
          :disabled="categories.length === 0"
          :value="modelValue.categoryId"
          @change="patch({ categoryId: ($event.target as HTMLSelectElement).value })"
        >
          <option value="">全部分类</option>
          <option v-for="item in categories" :key="item.id" :value="String(item.id)">
            {{ item.name }}
          </option>
        </select>
      </div>

      <div class="filter-bar__field filter-bar__field--grow">
        <label class="filter-bar__label" for="filter-keyword">关键词</label>
        <input
          id="filter-keyword"
          class="filter-bar__control"
          type="search"
          placeholder="标题 / 描述里的内容"
          :value="modelValue.keyword"
          @input="patch({ keyword: ($event.target as HTMLInputElement).value })"
        />
      </div>

      <button type="button" class="btn btn--ghost filter-bar__reset" @click="reset">重置</button>
    </div>

    <p v-if="categoryHint" class="filter-bar__hint">{{ categoryHint }}</p>
  </form>
</template>

<style scoped>
.filter-bar {
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.filter-bar__row {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-3);
}

.filter-bar__field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  min-width: 0;
}

.filter-bar__label {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.filter-bar__control {
  width: 100%;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface);
  color: var(--color-text);
  font: inherit;
  font-size: var(--fs-md);
}

.filter-bar__control:disabled {
  background-color: var(--color-surface-muted);
  color: var(--color-text-muted);
}

.filter-bar__hint {
  margin-top: var(--sp-2);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

@media (min-width: 768px) {
  .filter-bar__row {
    grid-template-columns: repeat(4, minmax(0, 1fr)) auto;
    align-items: end;
  }

  .filter-bar__field--grow {
    grid-column: auto;
  }
}
</style>
