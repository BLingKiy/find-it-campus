<script setup lang="ts">
// 匹配区三段状态（未开始 / 结果 / 不可用）。基线 §6 硬要求：
// 30002/30003 只降级本区，详情主体照常可用，不许整页报错。
import { computed } from 'vue'
import { match } from '@/api/match'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import LoadingBlock from '@/components/common/LoadingBlock.vue'
import MatchItemCard from '@/components/match/MatchItemCard.vue'
import { useRequest } from '@/composables/useRequest'
import type { RecordType } from '@/types/api'

const props = defineProps<{
  seedText: string
  excludeRecordId?: number | null
  /** 想找的记录类型：失主看详情 ⇒ 找招领帖（FOUND），反之亦然（契约 §8.1） */
  wantType?: RecordType
  categoryId?: number | null
}>()

const { data, error, loading, run } = useRequest(match, { immediate: false })

const items = computed(() => data.value?.items ?? [])
const engineText = computed(() =>
  data.value?.engine === 'EXTERNAL_EMBEDDING' ? '外部向量模型' : '本地关键词匹配'
)

async function findRelated(): Promise<void> {
  await run({
    text: props.seedText,
    ...(props.wantType ? { type: props.wantType } : {}),
    ...(props.categoryId ? { categoryId: props.categoryId } : {}),
    topN: 5,
    // 后端/契约默认值就是 0.3，这里显式写出是为了让「阈值属于实现参数」这件事在前端可见
    minScore: 0.3,
    excludeRecordId: props.excludeRecordId ?? null
  })
}
</script>

<template>
  <section class="match-panel">
    <header class="match-panel__head">
      <div>
        <h2 class="match-panel__title">帮我找找相关启事</h2>
        <p class="match-panel__hint">按这段描述在本地候选池里找相似启事，排除本条自身</p>
      </div>
      <button type="button" class="btn btn--primary" :disabled="loading" @click="findRelated">
        {{ loading ? '匹配中…' : '开始匹配' }}
      </button>
    </header>

    <!-- 错误只在本区内展示（含 30002/30003 的降级文案，见 describeError） -->
    <ErrorBanner v-if="error" :error="error" class="u-mt" />
    <LoadingBlock v-else-if="loading" :rows="2" class="u-mt" />
    <EmptyState
      v-else-if="data && items.length === 0"
      class="u-mt"
      title="暂无匹配结果"
      description="没有相似度达到阈值的启事，换个描述或稍后再试"
    />

    <template v-else-if="items.length > 0">
      <p class="match-panel__summary u-mt">
        共 {{ data?.total }} 条，用时 {{ data?.tookMs }}ms，引擎：{{ engineText }}
      </p>
      <ul class="match-panel__list">
        <li v-for="item in items" :key="item.recordId">
          <MatchItemCard :item="item" />
        </li>
      </ul>
    </template>

    <p v-else class="match-panel__idle u-mt">点「开始匹配」后这里会列出相似度最高的几条启事。</p>
  </section>
</template>

<style scoped>
.match-panel {
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.match-panel__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}

.match-panel__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-medium);
}

.match-panel__hint {
  margin-top: var(--sp-1);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.match-panel__summary {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.match-panel__list {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-3);
  margin-top: var(--sp-2);
}

.match-panel__idle {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}
</style>
