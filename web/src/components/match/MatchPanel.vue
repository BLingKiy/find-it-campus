<script setup lang="ts">
// 匹配区四段状态（未开始 / 加载 / 不可用 / 结果 / 空）。基线 §6 硬要求：
// 30002/30003 只降级本区，详情主体照常可用，不许整页报错。
// 4.2：结果区补上「排序可见」——名次 + 明确的排序说明 + 引擎与时延。
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
  <section class="match-panel panel" aria-labelledby="match-panel-title">
    <header class="panel__head">
      <div>
        <h2 id="match-panel-title" class="panel__title">帮我找找相关启事</h2>
        <p class="panel__desc">
          按这段描述在候选池里找相似启事，排除本条自身；结果按相似度从高到低排列。
        </p>
      </div>
      <button type="button" class="btn btn--primary" :disabled="loading" @click="findRelated">
        <span v-if="loading" class="btn__spinner" aria-hidden="true"></span>
        {{ loading ? '匹配中…' : data ? '重新匹配' : '开始匹配' }}
      </button>
    </header>

    <!-- 4 段状态：错误只在本区内展示（含 30002/30003 的降级文案，见 describeError） -->
    <ErrorBanner
      v-if="error"
      :error="error"
      title="匹配服务暂不可用"
      retry-label="重试"
      @retry="findRelated"
    />

    <LoadingBlock v-else-if="loading" variant="inline" :rows="2" label="正在匹配相似启事" />

    <EmptyState
      v-else-if="data && items.length === 0"
      title="暂无匹配结果"
      description="没有相似度达到阈值的启事，换个描述或稍后再试"
    />

    <template v-else-if="items.length > 0 && data">
      <p class="match-panel__summary" role="status">
        共 {{ data.total }} 条候选 · 用时 {{ data.tookMs }}ms · 引擎：{{ engineText }} ·
        阈值 {{ Math.round(data.minScore * 100) }}%
      </p>
      <ol class="match-panel__list">
        <li v-for="(item, index) in items" :key="item.recordId">
          <MatchItemCard :item="item" :rank="index + 1" />
        </li>
      </ol>
    </template>

    <p v-else class="match-panel__idle">
      点「开始匹配」后，这里会按相似度从高到低列出最相近的几条启事，并说明每条为什么被选中。
    </p>
  </section>
</template>

<style scoped>
.match-panel__summary {
  margin-bottom: var(--sp-3);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
}

.match-panel__list {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-3);
}

.match-panel__idle {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

@media (min-width: 768px) {
  .match-panel__list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
