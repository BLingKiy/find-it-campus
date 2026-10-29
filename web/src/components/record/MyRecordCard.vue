<script setup lang="ts">
// 「进行中」启事卡片（D2）：摘要 + 逐条 pair + 本人操作。
// 关键口径：
// - 「标记完成」只在 hasMatchedPair=true 时可用；不足时原生 disabled，点击不会发起请求，也不靠 409 兜底。
// - 「关闭启事」不受 pair 状态限制，走两段式确认。
// - pair 卡片直接复用 E2 的 PairCard；每对独立，处理一条不联动其他 pair。
import { computed, ref, watch } from 'vue'
import { patchStatus } from '@/api/records'
import { BizError, describeError } from '@/api/http'
import PairCardView from '@/components/match/PairCard.vue'
import RecordStatusTag from '@/components/record/RecordStatusTag.vue'
import RecordTypeTag from '@/components/record/RecordTypeTag.vue'
import type { MyRecordItem, PairActionResult, PairCard, RecordStatus } from '@/types/api'

const props = defineProps<{ record: MyRecordItem }>()
const emit = defineEmits<{
  changed: [payload: { id: number; status: RecordStatus }]
  /** 配对状态变化后让父级重拉，同步 hasMatchedPair / 计数 */
  refresh: []
}>()

const cards = ref<PairCard[]>([...props.record.pairs])
watch(
  () => props.record.pairs,
  (next) => {
    cards.value = [...next]
  }
)
watch(
  () => props.record.id,
  () => {
    cards.value = [...props.record.pairs]
    confirmingClose.value = false
    actionError.value = null
  }
)

const acting = ref<'COMPLETE' | 'CLOSE' | null>(null)
const confirmingClose = ref(false)
const actionError = ref<unknown>(null)

const isPending = computed(() => props.record.status === 'PENDING')
/** 发布闸门：审核态是"能不能对外可见"的独立状态，不能并进三态标签 */
const reviewBadge = computed(() => {
  if (props.record.reviewState === 'REJECTED') {
    return { text: '未通过审核', cls: 'badge--warning' }
  }
  if (props.record.reviewState === 'PENDING') {
    return {
      text: props.record.reviewManualRequired ? '待本人确认' : '审核中',
      cls: 'badge--pending'
    }
  }
  return { text: '已通过审核', cls: 'badge--completed' }
})
const showReviewHint = computed(
  () => props.record.reviewState === 'REJECTED' || props.record.reviewManualRequired
)
const isAnalyzing = computed(
  () => props.record.pairState === 'PENDING' || props.record.pairState === 'SCORING'
)
const degraded = computed(
  () =>
    props.record.pairDegraded ||
    props.record.pairState === 'DEGRADED' ||
    cards.value.some((card) => card.degraded)
)
const canComplete = computed(() => isPending.value && props.record.hasMatchedPair)
const completeReason = computed(() =>
  props.record.hasMatchedPair
    ? ''
    : `需要双方都确认后才能标记完成（当前有效匹配 ${props.record.matchedPairCount} 对）`
)

/** 「N 条可能相关」按 t_pair_score 行数（pairCount），不拿卡片数 relatedCount 顶替 */
const pairStatusText = computed(() => {
  if (isAnalyzing.value) return '分析中'
  if (props.record.pairState === 'DEGRADED') return '已降级'
  return props.record.pairCount > 0 ? `${props.record.pairCount} 条可能相关` : '已出结果'
})

const pairStatusClass = computed(() => {
  if (isAnalyzing.value) return 'badge--pending'
  if (props.record.pairState === 'DEGRADED' || degraded.value) return 'badge--warning'
  return props.record.pairCount > 0 ? 'badge--completed' : 'badge--neutral'
})

function describeActionError(err: unknown): string {
  if (err instanceof BizError && err.code === 20005) {
    return '还没有双方都确认的匹配，暂时不能标记完成。'
  }
  if (err instanceof BizError && err.code === 20002) {
    return '状态已被更新，请刷新后再试。'
  }
  return describeError(err)
}

async function markCompleted(): Promise<void> {
  // 双保险：按钮 disabled 已挡住点击，这里再挡一次，确保无 MATCHED pair 时零请求
  if (!canComplete.value || acting.value) return
  actionError.value = null
  acting.value = 'COMPLETE'
  try {
    const result = await patchStatus(props.record.id, 'COMPLETED')
    emit('changed', { id: result.id, status: result.status })
  } catch (err) {
    actionError.value = err
  } finally {
    acting.value = null
  }
}

async function confirmClose(): Promise<void> {
  if (!isPending.value || acting.value) return
  actionError.value = null
  acting.value = 'CLOSE'
  try {
    const result = await patchStatus(props.record.id, 'CLOSED')
    confirmingClose.value = false
    emit('changed', { id: result.id, status: result.status })
  } catch (err) {
    actionError.value = err
  } finally {
    acting.value = null
  }
}

function handlePairChanged(result: PairActionResult): void {
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
  <article class="my-record" data-testid="my-record-card" :data-record-id="record.id">
    <header class="my-record__head">
      <RecordTypeTag :type="record.type" />
      <RecordStatusTag :status="record.status" />
      <span class="badge" :class="reviewBadge.cls" data-testid="my-record-review-state">
        {{ reviewBadge.text }}
      </span>
      <span class="my-record__mine">我的启事</span>
      <span class="my-record__id">#{{ record.id }}</span>
    </header>

    <h3 class="my-record__title">
      <RouterLink
        v-if="record.detailVisible"
        :to="{ name: 'RecordDetail', params: { id: record.id } }"
        data-testid="my-record-detail-link"
      >
        {{ record.title }}
      </RouterLink>
      <template v-else>{{ record.title }}</template>
    </h3>

    <p class="my-record__meta">
      {{ record.categoryName ?? '未分类' }} · {{ record.location }} · 发生 {{ record.occurredAt }}
    </p>
    <p v-if="record.summary && record.summary !== record.title" class="my-record__meta">
      摘要：{{ record.summary }}
    </p>
    <p
      v-if="showReviewHint"
      class="alert alert--warning my-record__degraded"
      role="status"
      data-testid="my-record-review-hint"
    >
      <span class="alert__title">
        {{ record.reviewState === 'REJECTED' ? '未通过审核' : '待本人确认' }}
      </span>
      <span class="alert__text">
        {{
          record.reviewReason ||
          'AI 审核暂不可用：可在详情页「确认发布」先用脱敏文本上架（不会补审），或等待审核恢复。'
        }}
      </span>
    </p>

    <div class="my-record__match" data-testid="my-record-match">
      <span class="badge" :class="pairStatusClass" data-testid="my-record-pair-state">
        {{ pairStatusText }}
      </span>
      <span v-if="degraded" class="badge badge--warning" data-testid="my-record-degraded">已降级</span>
      <span
        v-if="record.lowConfidenceCount > 0 && record.relatedCount > 0"
        class="my-record__count"
      >
        其中 {{ record.relatedCount }} 条可查看
      </span>
      <span v-if="record.lowConfidenceCount > 0" class="my-record__count">
        另有 {{ record.lowConfidenceCount }} 条低分结果（仅计数）
      </span>
    </div>

    <p v-if="isAnalyzing" class="hint my-record__analyzing" data-testid="my-record-analyzing-hint">
      匹配分析中，页面每 5 秒自动刷新；出分后会变成「N 条可能相关」。
    </p>
    <p
      v-if="degraded"
      class="alert alert--warning my-record__degraded"
      role="status"
      data-testid="my-record-degraded-hint"
    >
      <span class="alert__title">已降级</span>
      <span class="alert__text">智能匹配暂不可用，已用关键词匹配。分数仅供参考。</span>
    </p>

    <div class="my-record__actions">
      <RouterLink
        v-if="record.detailVisible"
        :to="{ name: 'RecordDetail', params: { id: record.id } }"
        class="btn btn--sm btn--ghost"
        data-testid="my-record-view"
      >
        查看详情
      </RouterLink>
      <RouterLink
        :to="{ name: 'RecordEdit', params: { id: record.id } }"
        class="btn btn--sm btn--ghost"
        data-testid="my-record-edit"
      >
        编辑
      </RouterLink>

      <button
        type="button"
        class="btn btn--sm btn--ghost"
        :class="{ 'my-record__complete--blocked': !canComplete }"
        :disabled="!canComplete || acting !== null"
        :title="canComplete ? '' : completeReason"
        :aria-describedby="canComplete ? undefined : `my-complete-reason-${record.id}`"
        data-testid="my-record-complete"
        @click="markCompleted"
      >
        <span v-if="acting === 'COMPLETE'" class="btn__spinner" aria-hidden="true"></span>
        {{ acting === 'COMPLETE' ? '提交中…' : '标记完成' }}
      </button>

      <template v-if="!confirmingClose">
        <button
          type="button"
          class="btn btn--sm btn--danger"
          :disabled="acting !== null"
          data-testid="my-record-close"
          @click="confirmingClose = true"
        >
          关闭启事
        </button>
      </template>
      <template v-else>
        <span class="my-record__confirm" role="status">关闭后不再公开展示，且不可恢复</span>
        <button
          type="button"
          class="btn btn--sm btn--danger"
          :disabled="acting !== null"
          data-testid="my-record-close-confirm"
          @click="confirmClose"
        >
          <span v-if="acting === 'CLOSE'" class="btn__spinner" aria-hidden="true"></span>
          确认关闭
        </button>
        <button
          type="button"
          class="btn btn--sm btn--ghost"
          :disabled="acting !== null"
          data-testid="my-record-close-cancel"
          @click="confirmingClose = false"
        >
          取消
        </button>
      </template>
    </div>

    <p
      v-if="isPending && !canComplete"
      :id="`my-complete-reason-${record.id}`"
      class="hint my-record__reason"
      data-testid="my-record-complete-reason"
    >
      {{ completeReason }}
    </p>

    <p v-if="actionError" class="my-record__error" role="alert" data-testid="my-record-action-error">
      {{ describeActionError(actionError) }}
    </p>

    <section
      v-if="cards.length > 0"
      class="my-record__pairs"
      aria-label="这条启事的可能相关配对"
      data-testid="my-record-pairs"
    >
      <h4 class="my-record__pairs-title">
        可能相关（{{ cards.length }} 条可查看）· 每对独立处理
      </h4>
      <ol class="my-record__pair-list">
        <li v-for="card in cards" :key="card.pairId">
          <PairCardView :card="card" @changed="handlePairChanged" @refresh="emit('refresh')" />
        </li>
      </ol>
    </section>
  </article>
</template>

<style scoped>
.my-record {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.my-record__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}

.my-record__mine {
  padding: 0 var(--sp-2);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-soft);
  color: var(--color-primary-strong);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: 22px;
}

.my-record__id {
  margin-left: auto;
  color: var(--color-text-subtle);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.my-record__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  overflow-wrap: anywhere;
}

.my-record__meta {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  overflow-wrap: anywhere;
}

.my-record__match {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}

.my-record__count {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
}

.my-record__analyzing {
  margin: 0;
}

.my-record__degraded {
  margin: 0;
}

.my-record__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--color-border);
}

.my-record__confirm {
  color: var(--color-danger);
  font-size: var(--fs-xs);
}

/* 未达 MATCHED 时把「标记完成」做成明确的置灰块，避免看起来和可点按钮一样 */
.my-record__actions .btn.my-record__complete--blocked {
  border-style: dashed;
  border-color: var(--color-border-strong);
  background-color: var(--color-surface-sunken);
  color: var(--color-text-subtle);
}

.my-record__reason {
  margin: 0;
}

.my-record__error {
  color: var(--color-danger);
  font-size: var(--fs-sm);
}

.my-record__pairs {
  padding-top: var(--sp-3);
  border-top: 1px solid var(--color-border);
}

.my-record__pairs-title {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}

.my-record__pair-list {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-3);
  margin-top: var(--sp-2);
  min-width: 0;
}

@media (min-width: 900px) {
  .my-record__pair-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* 320px：状态与操作各占一行、按钮全宽，文档不出现横向滚动 */
@media (max-width: 420px) {
  .my-record {
    padding: var(--sp-3);
  }

  .my-record__actions .btn {
    flex: 1 1 100%;
  }

  .my-record__confirm {
    flex-basis: 100%;
  }
}
</style>
