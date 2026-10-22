<script setup lang="ts">
// 详情页（基线 §2）：id 非正整数直接跳 NotFound，不带非法 id 去打接口；
// 状态机在后端（allowedStatuses），前端不自己推流转规则。
// 4.2：h1 固定为「启事详情」以保证五状态结构一致，记录标题降为 h2；
// 状态流转区在无可用流转时给出明确说明（原来直接不渲染，用户会以为页面缺了一块）。
import { computed, onBeforeMount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRecord, patchStatus } from '@/api/records'
import { BizError } from '@/api/http'
import RecordStatusTag from '@/components/record/RecordStatusTag.vue'
import RecordTypeTag from '@/components/record/RecordTypeTag.vue'
import MatchPanel from '@/components/match/MatchPanel.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import LoadingBlock from '@/components/common/LoadingBlock.vue'
import { useRequest } from '@/composables/useRequest'
import type { RecordStatus } from '@/types/api'

const route = useRoute()
const router = useRouter()

const recordId = computed(() => Number(route.params.id))
const isValidId = computed(() => Number.isInteger(recordId.value) && recordId.value > 0)

onBeforeMount(() => {
  if (isValidId.value) return
  void router.replace({
    name: 'NotFound',
    params: { pathMatch: route.path.slice(1).split('/') }
  })
})

const { data: record, error, loading, run } = useRequest(getRecord, { immediate: false })
// 同一组件复用于不同 id（卡片 A → 卡片 B）时也要跟着变
watch(
  recordId,
  (id) => {
    if (isValidId.value) void run(id)
  },
  { immediate: true }
)

/** 20001 单独展示：这是业务空态，不是「请求失败」（契约 §9） */
const notFound = computed(() => error.value instanceof BizError && error.value.code === 20001)

const { error: statusError, loading: statusLoading, run: runPatchStatus } = useRequest(patchStatus, {
  immediate: false
})

async function changeStatus(status: RecordStatus): Promise<void> {
  const outcome = await runPatchStatus(recordId.value, status)
  if (!outcome) return
  // 成功后重新拉详情：allowedStatuses 由后端重新计算
  void run(recordId.value)
}

watch(statusError, (err) => {
  if (!err) return
  // 20002 说明记录被别人改过，重新拉一次拿回最新 allowedStatuses
  if (err instanceof BizError && err.code === 20002) void run(recordId.value)
})

const seedText = computed(() =>
  record.value ? `${record.value.title}。${record.value.description}` : ''
)

/** 提交页 replace 过来时带 ?created=1，只作为一次性成功反馈（刷新/回退不重复弹） */
const justCreated = computed(() => route.query.created === '1')

function reload(): void {
  if (isValidId.value) void run(recordId.value)
}

const STATUS_ACTION_LABEL: Record<RecordStatus, string> = {
  PENDING: '待认领',
  CLAIMED: '已认领',
  CLOSED: '已关闭'
}
</script>

<template>
  <section v-if="isValidId" class="record-detail">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">启事详情</h1>
        <p class="page-head__desc">看清单条启事，或让匹配找找可能相关的另一条。</p>
      </div>
      <div class="page-head__actions">
        <RouterLink :to="{ name: 'RecordList' }" class="btn btn--ghost">← 返回列表</RouterLink>
      </div>
    </header>

    <LoadingBlock v-if="loading" variant="detail" :rows="1" label="正在加载启事详情" />

    <template v-else-if="notFound">
      <EmptyState
        title="记录不存在或已删除"
        description="这条启事可能已被发布者删除，或链接里的编号不对"
        action-label="回到启事列表"
        :to="{ name: 'RecordList' }"
      />
    </template>

    <ErrorBanner v-else-if="error" :error="error" retry-label="重试" @retry="reload" />

    <template v-else-if="record">
      <p v-if="justCreated" class="alert alert--success record-detail__created" role="status">
        <span class="alert__title">发布成功</span>
        <span class="alert__text">这条启事已经出现在列表里了。</span>
      </p>

      <article class="panel record-detail__card">
        <div class="record-detail__tags">
          <RecordTypeTag :type="record.type" />
          <RecordStatusTag :status="record.status" />
          <span class="record-detail__id">#{{ record.id }}</span>
        </div>

        <h2 class="record-detail__title">{{ record.title }}</h2>

        <dl class="record-detail__facts">
          <div class="record-detail__fact">
            <dt>分类</dt>
            <dd>{{ record.categoryName ?? '未分类' }}</dd>
          </div>
          <div class="record-detail__fact">
            <dt>地点</dt>
            <dd>{{ record.location }}</dd>
          </div>
          <div class="record-detail__fact">
            <dt>发生时间</dt>
            <dd>{{ record.occurredAt }}</dd>
          </div>
          <div class="record-detail__fact">
            <dt>发布时间</dt>
            <dd>{{ record.createdAt }}</dd>
          </div>
          <div class="record-detail__fact">
            <dt>最后更新</dt>
            <dd>{{ record.updatedAt }}</dd>
          </div>
          <div class="record-detail__fact">
            <dt>联系方式</dt>
            <dd>{{ record.contact ?? '未填写' }}</dd>
          </div>
          <div class="record-detail__fact">
            <dt>联系人</dt>
            <dd>{{ record.publisherName ?? '未填写' }}</dd>
          </div>
        </dl>

        <div class="record-detail__body">
          <h3 class="record-detail__section-title">详细描述</h3>
          <p class="record-detail__description">{{ record.description }}</p>
        </div>

        <footer class="record-detail__actions">
          <template v-if="record.allowedStatuses.length > 0">
            <span class="record-detail__actions-label">更新状态</span>
            <button
              v-for="status in record.allowedStatuses"
              :key="status"
              type="button"
              class="btn btn--ghost"
              :disabled="statusLoading"
              @click="changeStatus(status)"
            >
              <span v-if="statusLoading" class="btn__spinner" aria-hidden="true"></span>
              标记为{{ STATUS_ACTION_LABEL[status] }}
            </button>
          </template>
          <p v-else class="hint">当前状态没有可执行的流转，启事已归档。</p>
        </footer>
        <ErrorBanner
          v-if="statusError"
          class="record-detail__status-error"
          :error="statusError"
          title="状态更新失败"
        />
      </article>

      <MatchPanel
        class="record-detail__match"
        :seed-text="seedText"
        :exclude-record-id="record.id"
        :want-type="record.type === 'LOST' ? 'FOUND' : 'LOST'"
        :category-id="record.categoryId"
      />
    </template>
  </section>
</template>

<style scoped>
.record-detail__created {
  margin-bottom: var(--sp-4);
}

.record-detail__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.record-detail__id {
  margin-left: auto;
  color: var(--color-text-subtle);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.record-detail__title {
  margin-top: var(--sp-3);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-bold);
  letter-spacing: -0.01em;
  line-height: var(--lh-tight);
}

.record-detail__facts {
  display: grid;
  /* 320px 两列；平板三列；宽屏四列（时间戳不会被挤断行） */
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-3) var(--sp-4);
  margin-top: var(--sp-4);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--color-border);
}

.record-detail__fact dt {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.record-detail__fact dd {
  margin-top: 2px;
  font-size: var(--fs-md);
  font-variant-numeric: tabular-nums;
}

.record-detail__body {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--color-border);
}

.record-detail__section-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--color-text-muted);
  letter-spacing: 0.02em;
}

.record-detail__description {
  max-width: var(--content-max);
  margin-top: var(--sp-2);
  white-space: pre-wrap;
  line-height: var(--lh-relaxed);
}

.record-detail__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--color-border);
}

.record-detail__actions-label {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.record-detail__status-error {
  margin-top: var(--sp-3);
}

.record-detail__match {
  margin-top: var(--sp-4);
}

@media (min-width: 768px) {
  /* 3/4 列而非 7 列：时间戳 14px 约 146px，7 列会把 "2026-10-19 17:30:00" 挤成两行 */
  .record-detail__facts {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (min-width: 1120px) {
  .record-detail__facts {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
</style>
