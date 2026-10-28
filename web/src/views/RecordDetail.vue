<script setup lang="ts">
// 详情页（基线 §2）：id 非正整数直接跳 NotFound，不带非法 id 去打接口；
// 状态机在后端（allowedStatuses），前端不自己推流转规则。
// C2：身份维度落到页面上 —— 非本人看到的是服务端遮蔽后的字段（contact 为 null），
// 联系方式位渲染「登录后可查看」占位并给登录引导（带 redirect 回本页）；
// 本人多一组管理入口（编辑 / 关闭）。真正的越权拦截仍在服务端。
import { computed, onBeforeMount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRecord, patchStatus } from '@/api/records'
import { BizError } from '@/api/http'
import RecordStatusTag from '@/components/record/RecordStatusTag.vue'
import RecordTypeTag from '@/components/record/RecordTypeTag.vue'
import MaskedField from '@/components/record/MaskedField.vue'
import OwnRecordActions from '@/components/record/OwnRecordActions.vue'
import MatchPanel from '@/components/match/MatchPanel.vue'
import PairPanel from '@/components/match/PairPanel.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import LoadingBlock from '@/components/common/LoadingBlock.vue'
import { useAuth } from '@/composables/useAuth'
import { useRequest } from '@/composables/useRequest'
import type { RecordStatus } from '@/types/api'

const route = useRoute()
const router = useRouter()
const { isAuthenticated } = useAuth()

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

/**
 * 404 分流（A1 §13）：已登录用户遇到 20001 统一回「我的启事」并提示，
 * 详情页不白屏 —— 典型场景是本人刚把启事标记完成 / 关闭（终态详情按 A9 就是 404）。
 * 匿名用户留在原地看空态，不能把人往一个还要登录的页面推。
 */
watch(error, (err) => {
  if (!(err instanceof BizError) || err.code !== 20001) return
  if (isAuthenticated.value) void router.replace({ name: 'MyRecords', query: { unavailable: '1' } })
})

/** 未登录点「查看联系方式」：走登录引导，并带回跳地址（只接受站内路径，白名单在路由层） */
function goLogin(): void {
  void router.push({ name: 'Login', query: { redirect: route.fullPath } })
}

const { error: statusError, loading: statusLoading, run: runPatchStatus } = useRequest(patchStatus, {
  immediate: false
})

/**
 * 详情页只保留「标记完成」这一个流转按钮：
 * 「关闭」由上方管理入口承担（两段式确认），同一页出现两个关闭按钮会让人不敢点。
 * 20005（未达 MATCHED）不刷新页面，原地给出后端文案。
 */
const canComplete = computed(
  () => record.value?.isMine === true && record.value.allowedStatuses.includes('COMPLETED')
)

async function markCompleted(): Promise<void> {
  const outcome = await runPatchStatus(recordId.value, 'COMPLETED' as RecordStatus)
  if (!outcome) return
  // 终态详情对所有者也是 404（A9）：直接回我的启事，避免刷新一次白屏
  void router.replace({ name: 'MyRecords', query: { updated: 'completed' } })
}

function handleOwnChanged(): void {
  void router.replace({ name: 'MyRecords', query: { updated: 'closed' } })
}

watch(statusError, (err) => {
  if (!err) return
  // 20002 说明记录被别人改过，重新拉一次拿回最新 allowedStatuses
  if (err instanceof BizError && err.code === 20002) void run(recordId.value)
})

const seedText = computed(() =>
  record.value ? `${record.value.title}。${record.value.description}` : ''
)

/** 前端不把 CLOSED 详情当降级内容渲染：即使拿到陈旧响应也按不可查看处理 */
const closedGuard = computed(() => record.value?.status === 'CLOSED')
const completed = computed(() => record.value?.status === 'COMPLETED')

/** 提交页 replace 过来时带 ?created=1，只作为一次性成功反馈（刷新/回退不重复弹） */
const justCreated = computed(() => route.query.created === '1')
const justUpdated = computed(() => route.query.updated === '1')

function reload(): void {
  if (isValidId.value) void run(recordId.value)
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
        <!-- 本人管理入口：编辑 / 关闭；非本人这一整块不渲染 -->
        <OwnRecordActions
          v-if="record?.isMine"
          :record-id="record.id"
          :status="record.status"
          :is-mine="record.isMine"
          variant="block"
          @changed="handleOwnChanged"
        />
      </div>
    </header>

    <LoadingBlock v-if="loading" variant="detail" :rows="1" label="正在加载启事详情" />

    <template v-else-if="notFound">
      <EmptyState
        title="记录不存在或不可查看"
        description="这条启事可能已被发布者删除、关闭，或链接里的编号不对"
        action-label="回到启事列表"
        :to="{ name: 'RecordList' }"
      />
    </template>

    <ErrorBanner v-else-if="error" :error="error" retry-label="重试" @retry="reload" />

    <EmptyState
      v-else-if="closedGuard"
      title="记录已关闭，不可查看"
      description="已关闭的启事不再提供详情；这是我的启事时可回到「我的启事」处理其他记录"
      action-label="回到启事列表"
      :to="{ name: 'RecordList' }"
    />

    <template v-else-if="record">
      <!-- vF：COMPLETED 只在门槛命中时可见，详情页给明确的已完成标识 -->
      <p
        v-if="completed"
        class="alert alert--success record-detail__completed"
        role="status"
        data-testid="detail-completed-badge"
      >
        <span class="alert__title">已完成</span>
        <span class="alert__text">
          这条启事已完成，不会再出现在公共列表；保留期内仍参与匹配，且仅合格对端可查看详情。
        </span>
      </p>

      <!-- 身份提示：匿名看遮蔽态；登录非本人看非发布者视角 -->
      <p
        v-if="!isAuthenticated"
        class="alert alert--info record-detail__identity"
        role="status"
        data-testid="anonymous-banner"
      >
        <span class="alert__title">你正在匿名浏览</span>
        <span class="alert__text">
          登录后可管理你发布的启事，并在配对确认后查看对方联系方式。
        </span>
        <button type="button" class="btn btn--sm btn--ghost alert__action" @click="goLogin">
          去登录
        </button>
      </p>
      <p
        v-else-if="!record.isMine"
        class="alert alert--info record-detail__identity"
        role="status"
        data-testid="non-owner-banner"
      >
        <span class="alert__title">非发布者视角</span>
        <span class="alert__text">
          完整描述、联系方式与联系人按遮蔽规则展示；只有发布者本人能编辑或关闭。
        </span>
      </p>

      <p v-if="justCreated" class="alert alert--success record-detail__created" role="status">
        <span class="alert__title">
          {{ record.reviewState === 'PENDING' ? '已提交，待审核' : '发布成功' }}
        </span>
        <span class="alert__text">
          {{
            record.reviewState === 'PENDING'
              ? '审核通过后才会出现在公共列表与匹配结果中；可在「我的启事」查看审核进度。'
              : '这条启事已经出现在列表里了。'
          }}
        </span>
      </p>

      <p v-else-if="justUpdated" class="alert alert--success record-detail__created" role="status">
        <span class="alert__title">
          {{ record.reviewState === 'PENDING' ? '修改已保存，重新审核中' : '修改已保存' }}
        </span>
        <span class="alert__text">
          {{
            record.reviewState === 'PENDING'
              ? '内容变更后需重新审核；通过前不会对外可见。'
              : '详情已刷新为最新内容。'
          }}
        </span>
      </p>

      <p
        v-if="record.isMine && record.reviewState && record.reviewState !== 'APPROVED'"
        class="alert alert--warning"
        role="status"
        data-testid="detail-review-state"
      >
        <span class="alert__title">
          {{ record.reviewState === 'REJECTED' ? '未通过审核' : (record.reviewManualRequired ? '待人工审核' : '审核中') }}
        </span>
        <span class="alert__text">
          {{ record.reviewReason || '审核通过前，这条启事不会出现在公共列表、匹配或配对中。' }}
        </span>
      </p>

      <article class="panel record-detail__card">
        <div class="record-detail__tags">
          <RecordTypeTag :type="record.type" />
          <RecordStatusTag :status="record.status" />
          <span v-if="record.isMine" class="record-detail__mine" data-testid="detail-mine-badge">
            我的启事
          </span>
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
          <div class="record-detail__fact record-detail__fact--contact">
            <dt>联系方式</dt>
            <dd>
              <!-- 本人：明文或「未填写」；非本人：只渲染占位，服务端已把 contact 置空 -->
              <template v-if="record.isMine">
                <span data-testid="contact-owner">{{ record.contact ?? '未填写' }}</span>
              </template>
              <template v-else>
                <MaskedField hint="联系方式仅发布者本人及已确认配对的对方可见" />
                <button
                  v-if="!isAuthenticated"
                  type="button"
                  class="btn btn--sm btn--ghost record-detail__contact-cta"
                  data-testid="contact-login-cta"
                  @click="goLogin"
                >
                  查看联系方式
                </button>
              </template>
            </dd>
          </div>
          <div class="record-detail__fact">
            <dt>联系人</dt>
            <dd>{{ record.publisherName ?? (record.isMine ? '未填写' : '匿名同学') }}</dd>
          </div>
        </dl>

        <div class="record-detail__body">
          <h3 class="record-detail__section-title">详细描述</h3>
          <p class="record-detail__description" data-testid="detail-description">
            {{ record.description }}
          </p>
          <p v-if="!isAuthenticated" class="hint record-detail__mask-hint">
            登录后可参与匹配、管理自己的启事；联系方式在配对确认后可见。
          </p>
        </div>

        <!-- 原文仅本人可见（raw_*）；展示文本才是任何对外出口使用的那一份。 -->
        <details
          v-if="record.isMine && record.rawTitle && (record.rawTitle !== record.title || record.rawDescription !== record.description || record.rawLocation !== record.location)"
          class="record-detail__raw"
          data-testid="detail-raw"
        >
          <summary>查看原始内容（仅本人可见，读取会记录审计）</summary>
          <p><strong>标题：</strong>{{ record.rawTitle }}</p>
          <p><strong>地点：</strong>{{ record.rawLocation }}</p>
          <p><strong>描述：</strong>{{ record.rawDescription }}</p>
        </details>

        <!-- 状态操作只属于本人：非本人 allowedStatuses 恒为空，不能落到「已归档」的假说明上 -->
        <footer v-if="record.isMine" class="record-detail__actions">
          <template v-if="canComplete">
            <span class="record-detail__actions-label">更新状态</span>
            <button
              type="button"
              class="btn btn--ghost"
              :disabled="statusLoading"
              data-testid="action-complete"
              @click="markCompleted"
            >
              <span v-if="statusLoading" class="btn__spinner" aria-hidden="true"></span>
              标记为已完成
            </button>
          </template>
          <p v-else class="hint" data-testid="complete-hint">
            当前状态没有可执行的流转；「标记完成」需要已有双方确认的匹配（当前有效匹配
            {{ record.matchedPairCount }} 对）。
          </p>
        </footer>

        <ErrorBanner
          v-if="statusError"
          class="record-detail__status-error"
          :error="statusError"
          title="状态更新失败"
        />
      </article>

      <!-- 本人看真实配对（pairs[] 仅所有者返回）；非本人保留手动匹配用于发现候选 -->
      <PairPanel
        v-if="record.isMine"
        class="record-detail__match"
        :pairs="record.pairs ?? []"
        :pair-state="record.pairState"
        :low-confidence-count="record.lowConfidenceCount"
        :record-status="record.status"
        @refresh="reload"
      />
      <MatchPanel
        v-else
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
.record-detail__identity,
.record-detail__created,
.record-detail__completed {
  margin-bottom: var(--sp-4);
}

.record-detail__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.record-detail__mine {
  padding: 0 var(--sp-2);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-soft);
  color: var(--color-primary-strong);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: 22px;
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

/* 联系方式可能同时有占位与按钮，允许换行而不是把占位挤成省略号 */
.record-detail__fact--contact dd {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-1) var(--sp-2);
}

.record-detail__contact-cta {
  padding: 0 var(--sp-2);
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

.record-detail__mask-hint {
  margin-top: var(--sp-2);
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
