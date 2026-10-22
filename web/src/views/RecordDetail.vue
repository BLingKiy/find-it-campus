<script setup lang="ts">
// 详情页（基线 §2）：id 非正整数直接跳 NotFound，不带非法 id 去打接口；
// 状态机在后端（allowedStatuses），前端不自己推流转规则。
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
</script>

<template>
  <section v-if="isValidId" class="record-detail">
    <LoadingBlock v-if="loading" :rows="4" />

    <EmptyState
      v-else-if="notFound"
      title="记录不存在或已删除"
      description="这条启事可能已被发布者删除"
      action-text="看看别的启事吧"
    />

    <ErrorBanner v-else-if="error" :error="error" />

    <template v-else-if="record">
      <template v-if="justCreated">
        <p class="record-detail__created" role="status">发布成功，这条已经是你的启事了</p>
      </template>

      <article class="record-detail__card">
        <header class="record-detail__head">
          <div class="record-detail__tags">
            <RecordTypeTag :type="record.type" />
            <RecordStatusTag :status="record.status" />
            <span class="record-detail__id">#{{ record.id }}</span>
          </div>
          <h1 class="record-detail__title">{{ record.title }}</h1>
        </header>

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
          <h2 class="record-detail__section-title">详细描述</h2>
          <p class="record-detail__description">{{ record.description }}</p>
        </div>

        <footer v-if="record.allowedStatuses.length > 0" class="record-detail__actions">
          <span class="record-detail__actions-label">更新状态：</span>
          <button
            v-for="status in record.allowedStatuses"
            :key="status"
            type="button"
            class="btn btn--ghost"
            :disabled="statusLoading"
            @click="changeStatus(status)"
          >
            标记为{{ status === 'CLAIMED' ? '已认领' : status === 'CLOSED' ? '已关闭' : status }}
          </button>
        </footer>
        <ErrorBanner v-if="statusError" :error="statusError" class="u-mt" />
      </article>

      <MatchPanel
        class="u-mt"
        :seed-text="seedText"
        :exclude-record-id="record.id"
        :want-type="record.type === 'LOST' ? 'FOUND' : 'LOST'"
        :category-id="record.categoryId"
      />
    </template>

    <RouterLink :to="{ name: 'RecordList' }" class="record-detail__back">← 返回列表</RouterLink>
  </section>
</template>

<style scoped>
.record-detail__created {
  margin-bottom: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--color-success);
  border-radius: var(--radius-md);
  background-color: var(--color-success-soft);
  color: var(--color-success);
  font-size: var(--fs-sm);
}

.record-detail__card {
  padding: var(--sp-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.record-detail__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.record-detail__id {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.record-detail__title {
  margin-top: var(--sp-3);
  font-size: var(--fs-xxl);
  font-weight: var(--fw-bold);
  line-height: var(--lh-tight);
}

.record-detail__facts {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-3);
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--color-border);
}

.record-detail__fact dt {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.record-detail__fact dd {
  margin: 0;
  margin-top: var(--sp-1);
  font-size: var(--fs-md);
}

.record-detail__body {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--color-border);
}

.record-detail__section-title {
  font-size: var(--fs-md);
  font-weight: var(--fw-medium);
}

.record-detail__description {
  margin-top: var(--sp-2);
  white-space: pre-wrap;
  line-height: var(--lh-normal);
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

.record-detail__back {
  display: inline-block;
  margin-top: var(--sp-5);
  font-size: var(--fs-sm);
}

@media (min-width: 768px) {
  .record-detail__facts {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
