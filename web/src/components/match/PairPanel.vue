<script setup lang="ts">
// 详情页配对区（E2）：一条记录可同时拥有多对，逐条展示、各自操作、互不影响。
// 卡片只从详情响应的 pairs[] 来；<0.75 的服务端不出卡片，只给 lowConfidenceCount。
import { computed, ref, watch } from 'vue'
import PairCardView from '@/components/match/PairCard.vue'
import type { PairActionResult, PairCard } from '@/types/api'

const props = defineProps<{
  pairs: PairCard[]
  pairState: string | null
  lowConfidenceCount: number
  recordStatus: string
}>()

const emit = defineEmits<{ refresh: [] }>()

const cards = ref<PairCard[]>([...props.pairs])

watch(
  () => props.pairs,
  (next) => {
    cards.value = [...next]
  }
)

const hasDegraded = computed(
  () => props.pairState === 'DEGRADED' || cards.value.some((card) => card.degraded)
)
const analyzing = computed(
  () => props.pairState === 'PENDING' || props.pairState === 'SCORING'
)
const ready = computed(() => props.pairState === 'READY' || props.pairState === 'DEGRADED')

function handleChanged(result: PairActionResult): void {
  // 只改这一张卡的状态：其他 pair 不重拉、不联动（任务口径：配对单元 1:1）
  const target = cards.value.find((card) => card.pairId === result.pairId)
  if (!target) return
  target.status = result.status
  target.matched = result.matched
  target.myConfirmed = result.myConfirmed
  target.otherConfirmed = result.otherConfirmed
  target.lostConfirmedAt = result.lostConfirmedAt
  target.foundConfirmedAt = result.foundConfirmedAt
}
</script>

<template>
  <section class="pair-panel panel" aria-labelledby="pair-panel-title" data-testid="pair-panel">
    <header class="panel__head">
      <div>
        <h2 id="pair-panel-title" class="panel__title">
          可能相关<template v-if="cards.length > 0">（{{ cards.length }} 条可查看）</template>
        </h2>
        <p class="panel__desc">
          按匹配度从高到低排列。每一对独立处理：标记联系、确认或忽略其中一条，不影响其他启事。
        </p>
      </div>
    </header>

    <p
      v-if="hasDegraded"
      class="alert alert--warning pair-panel__degraded"
      role="status"
      data-testid="pair-degraded-banner"
    >
      <span class="alert__title">已降级</span>
      <span class="alert__text">智能匹配暂不可用，已用关键词匹配。分数仅供参考。</span>
    </p>

    <p v-else-if="analyzing" class="alert alert--info pair-panel__degraded" role="status">
      <span class="alert__title">匹配分析中</span>
      <span class="alert__text">结果稍后出现在这里，可以稍后刷新本页查看。</span>
    </p>

    <ol v-if="cards.length > 0" class="pair-panel__list">
      <li v-for="card in cards" :key="card.pairId">
        <PairCardView :card="card" @changed="handleChanged" @refresh="emit('refresh')" />
      </li>
    </ol>

    <p
      v-else-if="ready && lowConfidenceCount === 0"
      class="pair-panel__empty"
      data-testid="pair-empty"
    >
      暂时没有达到展示门槛的可能相关启事。新的寻物 / 招领发布后，匹配会自动重算。
    </p>

    <!-- <0.75：只给条数，不渲染任何卡片、按钮或号码片段 -->
    <div
      v-if="lowConfidenceCount > 0"
      class="pair-panel__low"
      data-testid="pair-low-count"
    >
      <p class="pair-panel__low-title">存在 {{ lowConfidenceCount }} 条可能相关</p>
      <p class="hint">
        匹配度低于 75%，仅显示数量，不展示详情与联系方式。
      </p>
    </div>

    <p v-if="recordStatus === 'COMPLETED'" class="hint pair-panel__completed-hint">
      这条启事已完成。保留期内它仍参与匹配，但不会再出现在公共列表里。
    </p>
  </section>
</template>

<style scoped>
.pair-panel__degraded {
  margin-bottom: var(--sp-3);
}

.pair-panel__list {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-3);
  min-width: 0;
}

.pair-panel__empty {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.pair-panel__low {
  margin-top: var(--sp-3);
  padding: var(--sp-3);
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface-muted);
}

.pair-panel__low-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  overflow-wrap: anywhere;
}

.pair-panel__completed-hint {
  margin-top: var(--sp-3);
}

@media (min-width: 900px) {
  .pair-panel__list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
