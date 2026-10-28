<script setup lang="ts">
// 单张配对卡片（契约 §5.1）：一条 LOST↔FOUND 就是一张卡，四态互不影响。
// 这一对处理完不改变其他卡；页面不出现「已解决」—— MATCHED 只表示两端都确认过。
import { computed, ref, watch } from 'vue'
import { BizError } from '@/api/http'
import { actOnPair, getPairContact, unlockPairContact, PAIR_STATUS_LABELS } from '@/api/pairs'
import RecordStatusTag from '@/components/record/RecordStatusTag.vue'
import RecordTypeTag from '@/components/record/RecordTypeTag.vue'
import type { PairActionResult, PairCard, PairStatus } from '@/types/api'

const props = defineProps<{ card: PairCard }>()
const emit = defineEmits<{
  changed: [result: PairActionResult]
  /** CONFIRM 双端齐时通知详情页重拉计数（「标记完成」按钮依赖它） */
  refresh: []
}>()

const status = ref<PairStatus>(props.card.status)
const myConfirmed = ref(props.card.myConfirmed)
const otherConfirmed = ref(props.card.otherConfirmed)
const matched = ref(props.card.matched)
const myUnlocked = ref(props.card.myUnlocked)
const noteDraft = ref(props.card.myNote ?? '')

const contact = ref<string | null>(null)
const contactNote = ref<string | null>(null)
const contactMessage = ref<string | null>(null)
const contactError = ref<unknown>(null)
const contactLoading = ref(false)

const actionLoading = ref<'CONTACT' | 'CONFIRM' | 'IGNORE' | null>(null)
const actionMessage = ref<string | null>(null)
const actionError = ref<unknown>(null)

const unlockLoading = ref(false)
const unlockMessage = ref<string | null>(null)
const unlockError = ref<unknown>(null)

// 同一组件复用于不同 pair（列表重排/刷新）时，所有本地状态跟着卡片重置
watch(
  () => props.card.pairId,
  () => {
    status.value = props.card.status
    myConfirmed.value = props.card.myConfirmed
    otherConfirmed.value = props.card.otherConfirmed
    matched.value = props.card.matched
    myUnlocked.value = props.card.myUnlocked
    noteDraft.value = props.card.myNote ?? ''
    contact.value = null
    contactNote.value = null
    contactMessage.value = null
    contactError.value = null
    actionMessage.value = null
    actionError.value = null
    unlockMessage.value = null
    unlockError.value = null
  }
)

// 父级重拉后（同 pairId、新数据）同步服务端事实；本地刚提交的状态与这些值一致，不会被覆盖
watch(
  () => props.card.status,
  (value) => {
    status.value = value
  }
)
watch(
  () => props.card.myUnlocked,
  (value) => {
    myUnlocked.value = value
  }
)
watch(
  () => props.card.myNote,
  (value) => {
    noteDraft.value = value ?? ''
  }
)
// 对端撤回 / 详情重拉后 contactAvailable=false：立即清掉本页已显示的号码
watch(
  () => props.card.contactAvailable,
  (value) => {
    if (value) return
    contact.value = null
    contactNote.value = null
  }
)

const noteCount = computed(() => Array.from(noteDraft.value).length)
const noteTooLong = computed(() => noteCount.value > 200)
const isTerminal = computed(() => status.value === 'MATCHED' || status.value === 'IGNORED')
const canMarkContacted = computed(() => status.value === 'NEW')
const canConfirm = computed(
  () => (status.value === 'NEW' || status.value === 'CONTACTED') && !myConfirmed.value
)
const canIgnore = computed(() => status.value === 'NEW' || status.value === 'CONTACTED')
const isAuthorizable = computed(() => props.card.tier === 'AUTHORIZABLE')

/** 档位不足/配对失效/越权/不存在分别给不同文案，不只甩后端 message */
function describePairError(err: unknown): string {
  if (!(err instanceof BizError)) return '操作失败，请稍后再试'
  switch (err.code) {
    case 50002:
      return '匹配度不足，无法查看或公开联系方式'
    case 50005:
      return '该配对已失效（对方启事已关闭或完成）'
    case 20004:
      return '配对不存在，或对方启事已被删除'
    case 10007:
      return '你不是这条配对的参与方'
    case 10006:
      return '登录已失效，请重新登录'
    case 50003:
      return '这条配对已进入终态，不能再修改'
    default:
      return err.message || '操作失败，请稍后再试'
  }
}

async function loadContact(): Promise<void> {
  if (contactLoading.value) return
  contactError.value = null
  contactMessage.value = null
  contactLoading.value = true
  try {
    const view = await getPairContact(props.card.pairId)
    if (view.available && view.contact) {
      contact.value = view.contact
      contactNote.value = view.note
      contactMessage.value = null
    } else {
      contact.value = null
      contactNote.value = null
      contactMessage.value = view.message || '对方尚未公开'
    }
  } catch (err) {
    contactError.value = err
  } finally {
    contactLoading.value = false
  }
}

async function runAction(action: 'CONTACT' | 'CONFIRM' | 'IGNORE'): Promise<void> {
  if (actionLoading.value) return
  actionError.value = null
  actionMessage.value = null
  actionLoading.value = action
  try {
    const result = await actOnPair(props.card.pairId, action)
    status.value = result.status
    matched.value = result.matched
    myConfirmed.value = result.myConfirmed
    otherConfirmed.value = result.otherConfirmed
    actionMessage.value =
      action === 'CONTACT'
        ? '已标记为「已联系」，其他配对状态不变'
        : action === 'IGNORE'
          ? '已忽略这一条，其他启事仍可继续处理'
          : result.matched
            ? '双方都已确认，可回到记录操作区标记完成'
            : '已确认，等待对方确认'
    emit('changed', result)
    if (result.matched) emit('refresh')
  } catch (err) {
    actionError.value = err
  } finally {
    actionLoading.value = null
  }
}

async function togglePublic(): Promise<void> {
  if (unlockLoading.value) return
  if (!myUnlocked.value && noteTooLong.value) {
    unlockError.value = new BizError({
      message: '附言最多 200 字',
      code: 10001,
      status: 400
    })
    return
  }
  unlockError.value = null
  unlockMessage.value = null
  unlockLoading.value = true
  try {
    const next = !myUnlocked.value
    const result = await unlockPairContact(
      props.card.pairId,
      next ? 'PUBLISH' : 'REVOKE',
      next ? noteDraft.value : null
    )
    myUnlocked.value = result.public
    if (result.public) {
      noteDraft.value = result.note ?? noteDraft.value
      unlockMessage.value = '已公开你的联系方式，对方现在可以查看'
    } else {
      // 撤回即清空附言（契约 §4.15），输入框同步清空，避免界面显示库里已不存在的内容
      noteDraft.value = ''
      unlockMessage.value = '已撤回，对方不再能看到你的联系方式'
    }
  } catch (err) {
    unlockError.value = err
  } finally {
    unlockLoading.value = false
  }
}

async function saveNote(): Promise<void> {
  if (noteTooLong.value) {
    unlockError.value = new BizError({ message: '附言最多 200 字', code: 10001, status: 400 })
    return
  }
  unlockLoading.value = true
  unlockError.value = null
  unlockMessage.value = null
  try {
    const result = await unlockPairContact(props.card.pairId, 'PUBLISH', noteDraft.value)
    myUnlocked.value = result.public
    noteDraft.value = result.note ?? noteDraft.value
    unlockMessage.value = '附言已更新（已打码，最多 200 字）'
  } catch (err) {
    unlockError.value = err
  } finally {
    unlockLoading.value = false
  }
}
</script>

<template>
  <article
    class="pair-card"
    :data-pair-id="card.pairId"
    data-testid="pair-card"
    :aria-label="`与启事 #${card.counterpart.recordId} 的配对`"
  >
    <header class="pair-card__head">
      <span class="pair-card__score" :data-band="card.scorePercent >= 75 ? 'high' : 'mid'">
        <span class="pair-card__score-value" data-testid="pair-score">{{ card.scorePercent }}%</span>
        <span class="pair-card__score-label">匹配度</span>
      </span>
      <span
        class="badge"
        :class="isAuthorizable ? 'badge--pending' : 'badge--completed'"
        data-testid="pair-tier"
      >
        {{ isAuthorizable ? '可能相关' : '高置信匹配' }}
      </span>
      <span class="badge badge--dot badge--closed" data-testid="pair-status">
        {{ PAIR_STATUS_LABELS[status] }}
      </span>
      <span v-if="card.degraded" class="badge badge--warning" data-testid="pair-degraded">
        已降级
      </span>
      <span class="pair-card__id">#{{ card.pairId }}</span>
    </header>

    <div class="pair-card__counterpart">
      <div class="pair-card__counterpart-head">
        <RecordTypeTag :type="card.counterpart.type" />
        <RecordStatusTag :status="card.counterpart.status" />
        <span class="pair-card__record-id">#{{ card.counterpart.recordId }}</span>
      </div>
      <h3 class="pair-card__title">
        <RouterLink
          v-if="card.counterpart.status === 'PENDING'"
          :to="{ name: 'RecordDetail', params: { id: card.counterpart.recordId } }"
          data-testid="pair-counterpart-link"
        >
          {{ card.counterpart.title }}
        </RouterLink>
        <template v-else>{{ card.counterpart.title }}</template>
      </h3>
      <p class="pair-card__meta">
        {{ card.counterpart.location }} · 发生 {{ card.counterpart.occurredAt }}
      </p>
    </div>

    <!-- 0.75–0.90：本端授权开关 + 附言（≤200 字）；≥0.90 不需要授权，不给多余开关 -->
    <section v-if="isAuthorizable && status !== 'IGNORED'" class="pair-card__unlock">
      <div class="pair-card__unlock-row">
        <button
          type="button"
          class="pair-switch"
          role="switch"
          :aria-checked="myUnlocked"
          :disabled="unlockLoading"
          data-testid="pair-publish-switch"
          @click="togglePublic"
        >
          <span class="pair-switch__track" aria-hidden="true">
            <span class="pair-switch__thumb"></span>
          </span>
          <span class="pair-switch__text">
            {{ myUnlocked ? '已公开我的联系方式' : '公开我的联系方式' }}
          </span>
        </button>
        <span class="hint pair-card__unlock-hint">仅这一对可见，不影响其他配对</span>
      </div>

      <label class="pair-card__note-label" :for="`pair-note-${card.pairId}`">
        附言（选填，最多 200 字；会打码手机号）
      </label>
      <textarea
        :id="`pair-note-${card.pairId}`"
        v-model="noteDraft"
        class="pair-card__note"
        rows="2"
        maxlength="200"
        placeholder="例如：周六下午三点在图书馆门口等，看到请联系我"
        data-testid="pair-note"
      ></textarea>
      <div class="pair-card__note-foot">
        <span class="field__counter" :class="{ 'field__counter--over': noteTooLong }">
          {{ noteCount }}/200
        </span>
        <button
          v-if="myUnlocked"
          type="button"
          class="btn btn--sm btn--ghost"
          :disabled="unlockLoading || noteTooLong"
          data-testid="pair-note-save"
          @click="saveNote"
        >
          保存附言
        </button>
      </div>

      <p v-if="unlockMessage" class="pair-card__ok" role="status" data-testid="pair-unlock-message">
        {{ unlockMessage }}
      </p>
      <p v-if="unlockError" class="pair-card__error" role="alert">
        {{ describePairError(unlockError) }}
      </p>
    </section>

    <!-- 号码只在点击后由 §4.14 返回；未达档位/未授权时这里没有任何号码片段 -->
    <section v-if="status !== 'IGNORED'" class="pair-card__contact">
      <div class="pair-card__contact-row">
        <button
          type="button"
          class="btn btn--sm btn--primary"
          :disabled="contactLoading || !card.contactAvailable"
          :aria-describedby="`pair-contact-hint-${card.pairId}`"
          data-testid="pair-contact-button"
          @click="loadContact"
        >
          <span v-if="contactLoading" class="btn__spinner" aria-hidden="true"></span>
          {{ contactLoading ? '读取中…' : '查看联系方式' }}
        </button>
        <p
          :id="`pair-contact-hint-${card.pairId}`"
          class="hint pair-card__contact-hint"
          data-testid="pair-contact-hint"
        >
          {{ card.contactHint }}
        </p>
      </div>
      <p v-if="contact" class="pair-card__number" data-testid="pair-contact-number">
        {{ contact }}
      </p>
      <p v-if="contactNote" class="pair-card__contact-note" data-testid="pair-contact-note">
        对方附言：{{ contactNote }}
      </p>
      <p v-if="contactMessage" class="hint" role="status" data-testid="pair-contact-message">
        {{ contactMessage }}
      </p>
      <p v-if="contactError" class="pair-card__error" role="alert">
        {{ describePairError(contactError) }}
      </p>
    </section>

    <footer v-if="status !== 'IGNORED'" class="pair-card__actions">
      <button
        v-if="canMarkContacted"
        type="button"
        class="btn btn--sm btn--ghost"
        :disabled="actionLoading !== null"
        data-testid="pair-action-contact"
        @click="runAction('CONTACT')"
      >
        {{ actionLoading === 'CONTACT' ? '提交中…' : '标记已联系' }}
      </button>
      <button
        v-if="canConfirm"
        type="button"
        class="btn btn--sm btn--ghost"
        :disabled="actionLoading !== null"
        data-testid="pair-action-confirm"
        @click="runAction('CONFIRM')"
      >
        {{ actionLoading === 'CONFIRM' ? '提交中…' : '确认匹配' }}
      </button>
      <button
        v-if="canIgnore"
        type="button"
        class="btn btn--sm btn--subtle"
        :disabled="actionLoading !== null"
        data-testid="pair-action-ignore"
        @click="runAction('IGNORE')"
      >
        {{ actionLoading === 'IGNORE' ? '提交中…' : '忽略这条' }}
      </button>
      <span v-if="isTerminal" class="hint">
        {{
          status === 'MATCHED'
            ? '这一对已由双方确认；是否标记完成由记录本人决定'
            : '这一对已忽略，不影响其他启事'
        }}
      </span>
      <span v-if="otherConfirmed && !matched" class="hint">对方已确认，等你确认</span>
    </footer>

    <p v-if="actionMessage" class="pair-card__ok" role="status" data-testid="pair-action-message">
      {{ actionMessage }}
    </p>
    <p v-if="actionError" class="pair-card__error" role="alert">
      {{ describePairError(actionError) }}
    </p>
  </article>
</template>

<style scoped>
.pair-card {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
  padding: var(--sp-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.pair-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}

.pair-card__score {
  display: inline-flex;
  align-items: baseline;
  gap: 2px;
  padding: 2px var(--sp-2);
  border-radius: var(--radius-xs);
  background-color: var(--color-success-soft);
  color: var(--color-success-strong);
  font-variant-numeric: tabular-nums;
}

.pair-card__score[data-band='mid'] {
  background-color: var(--color-warning-soft);
  color: var(--color-warning);
}

.pair-card__score-value {
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
}

.pair-card__score-label {
  font-size: var(--fs-xs);
}

.pair-card__id {
  margin-left: auto;
  color: var(--color-text-subtle);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.pair-card__counterpart {
  min-width: 0;
  padding: var(--sp-3);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface-muted);
}

.pair-card__counterpart-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.pair-card__record-id {
  margin-left: auto;
  color: var(--color-text-subtle);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.pair-card__title {
  margin-top: var(--sp-2);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  overflow-wrap: anywhere;
}

.pair-card__meta {
  margin-top: var(--sp-1);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  overflow-wrap: anywhere;
}

.pair-card__unlock {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--color-border);
}

.pair-card__unlock-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.pair-switch {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 4px var(--sp-2);
  border: 1px solid var(--color-control-border);
  border-radius: var(--radius-pill);
  background-color: var(--color-surface);
  color: var(--color-text);
  font-size: var(--fs-sm);
  cursor: pointer;
}

.pair-switch[aria-checked='true'] {
  border-color: var(--color-primary);
  background-color: var(--color-primary-soft);
  color: var(--color-primary-strong);
}

.pair-switch:disabled {
  cursor: wait;
  opacity: 0.7;
}

.pair-switch__track {
  position: relative;
  display: inline-block;
  width: 34px;
  height: 20px;
  border-radius: var(--radius-pill);
  background-color: var(--color-surface-sunken);
  transition: background-color var(--dur-fast) var(--ease-standard);
}

.pair-switch[aria-checked='true'] .pair-switch__track {
  background-color: var(--color-primary);
}

.pair-switch__thumb {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background-color: var(--color-surface);
  box-shadow: var(--shadow-sm);
  transition: transform var(--dur-fast) var(--ease-standard);
}

.pair-switch[aria-checked='true'] .pair-switch__thumb {
  transform: translateX(14px);
}

.pair-switch__text {
  white-space: normal;
  text-align: left;
}

.pair-card__unlock-hint {
  margin: 0;
}

.pair-card__note-label {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.pair-card__note {
  width: 100%;
  min-height: 56px;
  padding: var(--sp-2);
  border: 1px solid var(--color-control-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface);
  color: var(--color-text);
  font: inherit;
  font-size: var(--fs-sm);
  line-height: var(--lh-normal);
  resize: vertical;
}

.pair-card__note:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 1px;
}

.pair-card__note-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
}

.pair-card__contact {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--color-border);
}

.pair-card__contact-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.pair-card__contact-hint {
  flex: 1 1 140px;
  min-width: 0;
  margin: 0;
}

.pair-card__number {
  font-family: var(--font-mono);
  font-size: var(--fs-lg);
  font-weight: var(--fw-bold);
  letter-spacing: 0.02em;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

.pair-card__contact-note {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  overflow-wrap: anywhere;
}

.pair-card__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--color-border);
}

.pair-card__ok {
  color: var(--color-success-strong);
  font-size: var(--fs-sm);
}

.pair-card__error {
  color: var(--color-danger);
  font-size: var(--fs-sm);
}

@media (max-width: 420px) {
  .pair-card {
    padding: var(--sp-3);
  }

  .pair-card__contact-row .btn,
  .pair-card__actions .btn {
    flex: 1 1 100%;
  }

  .pair-card__contact-hint {
    flex-basis: 100%;
  }
}
</style>
