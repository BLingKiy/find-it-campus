<script setup lang="ts">
// 提交页（基线 §2）：前端先校验（契约 §5.1 的字段与长度），后端 fieldErrors 为准再兜一层。
// FieldError 的入参就是契约的 fieldErrors 形状，所以前后端两类错误共用同一个展示组件。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createRecord } from '@/api/records'
import { loadCategories } from '@/api/categoryCache'
import { BizError } from '@/api/http'
import FieldError from '@/components/common/FieldError.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import { useRequest } from '@/composables/useRequest'
import type { CategoryItem, FieldErrorItem, RecordCreatePayload, RecordType } from '@/types/api'

const route = useRoute()
const router = useRouter()

const TITLE_MAX = 64
const DESC_MAX = 2000
const LOCATION_MAX = 128
const CONTACT_MAX = 64
const PUBLISHER_MAX = 32
/** 契约 §5.1：occurredAt 允许略晚于当前时间以容忍时钟偏差 */
const FUTURE_TOLERANCE_MS = 5 * 60 * 1000

const presetType = computed(() => {
  const raw = route.query.type
  return raw === 'LOST' || raw === 'FOUND' ? (raw as RecordType) : 'LOST'
})

const form = ref({
  type: presetType.value as RecordType,
  title: '',
  description: '',
  categoryId: '',
  location: '',
  occurredAt: '',
  contact: '',
  publisherName: ''
})

const submitted = ref(false)
const categories = ref<CategoryItem[]>([])
const categoryHint = ref('')

const { error, loading, run } = useRequest(createRecord, { immediate: false })
const backendErrors = computed<FieldErrorItem[]>(() =>
  error.value instanceof BizError ? error.value.fieldErrors : []
)

/** 本地校验只在点过提交后展示，避免边打字边报错 */
const localErrors = computed<FieldErrorItem[]>(() => (submitted.value ? validate() : []))

const fieldErrors = computed<FieldErrorItem[]>(() => {
  const merged = [...backendErrors.value]
  for (const item of localErrors.value) {
    if (!merged.some((existing) => existing.field === item.field)) merged.push(item)
  }
  return merged
})

function validate(): FieldErrorItem[] {
  const errors: FieldErrorItem[] = []
  const push = (field: string, reason: string) => errors.push({ field, reason })

  if (form.value.type !== 'LOST' && form.value.type !== 'FOUND') push('type', '请选择启事类型')

  const title = form.value.title.trim()
  if (!title) push('title', '请填写标题')
  else if (title.length < 2 || title.length > TITLE_MAX) push('title', `标题长度需在 2 到 ${TITLE_MAX} 之间`)

  const description = form.value.description.trim()
  if (!description) push('description', '请填写详细描述')
  else if (description.length < 2 || description.length > DESC_MAX)
    push('description', `描述长度需在 2 到 ${DESC_MAX} 之间`)

  const location = form.value.location.trim()
  if (!location) push('location', '请填写地点')
  else if (location.length > LOCATION_MAX) push('location', `地点长度不能超过 ${LOCATION_MAX} 个字`)

  if (!form.value.occurredAt) push('occurredAt', '请填写发生时间')
  else if (toApiDateTime(form.value.occurredAt) === null) push('occurredAt', '时间格式不正确')
  else if (new Date(form.value.occurredAt).getTime() > Date.now() + FUTURE_TOLERANCE_MS)
    push('occurredAt', '发生时间不能晚于当前时间')

  if (form.value.contact.length > CONTACT_MAX)
    push('contact', `联系方式长度不能超过 ${CONTACT_MAX} 个字`)
  if (form.value.publisherName.length > PUBLISHER_MAX)
    push('publisherName', `联系人长度不能超过 ${PUBLISHER_MAX} 个字`)

  return errors
}

/** datetime-local 的 2026-10-21T15:20 → 契约 §1 的 2026-10-21 15:20:00 */
function toApiDateTime(value: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value)
  if (!match) return null
  const [, yyyy, mm, dd, hh, min] = match
  return `${yyyy}-${mm}-${dd} ${hh}:${min}:00`
}

function focusFirstError(errors: FieldErrorItem[]): void {
  const target = document.getElementById(`field-${errors[0].field}`)
  if (!target) return
  target.scrollIntoView({ block: 'center', behavior: 'smooth' })
  target.focus()
}

async function submit(): Promise<void> {
  submitted.value = true
  const errors = validate()
  if (errors.length > 0) {
    focusFirstError(errors)
    return
  }

  const payload: RecordCreatePayload = {
    type: form.value.type,
    title: form.value.title.trim(),
    description: form.value.description.trim(),
    location: form.value.location.trim(),
    occurredAt: toApiDateTime(form.value.occurredAt) as string,
    categoryId: form.value.categoryId ? Number(form.value.categoryId) : null,
    contact: form.value.contact.trim() || null,
    publisherName: form.value.publisherName.trim() || null
  }

  const created = await run(payload)
  if (!created) {
    focusFirstError(backendErrors.value)
    return
  }
  // replace 防止「后退」再回到空表单；created=1 让详情页给出成功反馈（基线 §2）
  void router.replace({ name: 'RecordDetail', params: { id: created.id }, query: { created: '1' } })
}

onMounted(async () => {
  try {
    categories.value = await loadCategories()
  } catch {
    categoryHint.value = '分类加载失败，可以先不选分类提交'
  }
})

// ?type=FOUND 这类入口链接进来时同步预选（基线 §2）
watch(presetType, (type) => {
  form.value.type = type
})
</script>

<template>
  <section class="record-create">
    <h1 class="record-create__title">发布启事</h1>
    <p class="record-create__hint">带 * 的为必填项；描述越具体，语义匹配越准。</p>

    <form class="record-create__form" novalidate @submit.prevent="submit">
      <div class="record-create__field">
        <label class="record-create__label" for="field-type">类型 *</label>
        <div id="field-type" class="record-create__radios" tabindex="-1">
          <label class="record-create__radio">
            <input v-model="form.type" type="radio" name="type" value="LOST" />
            寻物启事（我丢了东西）
          </label>
          <label class="record-create__radio">
            <input v-model="form.type" type="radio" name="type" value="FOUND" />
            招领启事（我捡到东西）
          </label>
        </div>
        <FieldError :errors="fieldErrors" field="type" />
      </div>

      <div class="record-create__field">
        <label class="record-create__label" for="field-title">
          标题 *
          <span class="record-create__counter">{{ form.title.length }}/{{ TITLE_MAX }}</span>
        </label>
        <input
          id="field-title"
          v-model="form.title"
          class="record-create__control"
          type="text"
          maxlength="80"
          placeholder="例：图书馆丢了黑色钱包"
        />
        <FieldError :errors="fieldErrors" field="title" />
      </div>

      <div class="record-create__field">
        <label class="record-create__label" for="field-description">
          详细描述 *
          <span class="record-create__counter">{{ form.description.length }}/{{ DESC_MAX }}</span>
        </label>
        <textarea
          id="field-description"
          v-model="form.description"
          class="record-create__control record-create__control--area"
          rows="5"
          placeholder="写清丢失/捡到的时间、地点、物品特征，这些都会参与语义匹配"
        ></textarea>
        <FieldError :errors="fieldErrors" field="description" />
      </div>

      <div class="record-create__grid">
        <div class="record-create__field">
          <label class="record-create__label" for="field-categoryId">分类</label>
          <select
            id="field-categoryId"
            v-model="form.categoryId"
            class="record-create__control"
            :disabled="categories.length === 0"
          >
            <option value="">暂不分类</option>
            <option v-for="item in categories" :key="item.id" :value="String(item.id)">
              {{ item.name }}
            </option>
          </select>
          <FieldError :errors="fieldErrors" field="categoryId" />
        </div>

        <div class="record-create__field">
          <label class="record-create__label" for="field-location">地点 *</label>
          <input
            id="field-location"
            v-model="form.location"
            class="record-create__control"
            type="text"
            placeholder="例：图书馆三楼自习区"
          />
          <FieldError :errors="fieldErrors" field="location" />
        </div>

        <div class="record-create__field">
          <label class="record-create__label" for="field-occurredAt">发生时间 *</label>
          <input
            id="field-occurredAt"
            v-model="form.occurredAt"
            class="record-create__control"
            type="datetime-local"
          />
          <FieldError :errors="fieldErrors" field="occurredAt" />
        </div>

        <div class="record-create__field">
          <label class="record-create__label" for="field-contact">联系方式</label>
          <input
            id="field-contact"
            v-model="form.contact"
            class="record-create__control"
            type="text"
            placeholder="手机 / QQ / 微信号"
          />
          <FieldError :errors="fieldErrors" field="contact" />
        </div>

        <div class="record-create__field">
          <label class="record-create__label" for="field-publisherName">联系人称呼</label>
          <input
            id="field-publisherName"
            v-model="form.publisherName"
            class="record-create__control"
            type="text"
            placeholder="例：李同学"
          />
          <FieldError :errors="fieldErrors" field="publisherName" />
        </div>
      </div>

      <p v-if="categoryHint" class="record-create__field-hint">{{ categoryHint }}</p>
      <ErrorBanner v-if="error && backendErrors.length === 0" :error="error" />

      <div class="record-create__actions">
        <button type="submit" class="btn btn--primary" :disabled="loading">
          {{ loading ? '提交中…' : '提交启事' }}
        </button>
        <RouterLink :to="{ name: 'RecordList' }" class="btn btn--ghost">返回列表</RouterLink>
      </div>
    </form>
  </section>
</template>

<style scoped>
.record-create__title {
  font-size: var(--fs-xxl);
  font-weight: var(--fw-bold);
  line-height: var(--lh-tight);
}

.record-create__hint {
  margin-top: var(--sp-2);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.record-create__form {
  margin-top: var(--sp-4);
  padding: var(--sp-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-surface);
}

.record-create__field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  min-width: 0;
}

.record-create__grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-4);
  margin-top: var(--sp-4);
}

.record-create__label {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.record-create__counter {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-regular);
}

.record-create__control {
  width: 100%;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface);
  color: var(--color-text);
  font: inherit;
  font-size: var(--fs-md);
}

.record-create__control:disabled {
  background-color: var(--color-surface-muted);
  color: var(--color-text-muted);
}

.record-create__control--area {
  resize: vertical;
  line-height: var(--lh-normal);
}

.record-create__radios {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-4);
  padding: var(--sp-2) 0;
}

.record-create__radio {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-md);
  font-weight: var(--fw-regular);
}

.record-create__field-hint {
  margin-top: var(--sp-3);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.record-create__actions {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-top: var(--sp-5);
}

@media (min-width: 768px) {
  .record-create__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
