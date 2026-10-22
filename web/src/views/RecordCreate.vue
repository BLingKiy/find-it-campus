<script setup lang="ts">
// 提交页（基线 §2）：前端先校验（契约 §5.1 的字段与长度），后端 fieldErrors 为准再兜一层。
// FieldError 的入参就是契约的 fieldErrors 形状，所以前后端两类错误共用同一个展示组件。
// 4.2 改动仅限外观与可访问性：控件统一 .control、错误用 aria-invalid + aria-describedby 关联、
// 校验失败时给出页级错误摘要（可点回字段）；校验规则、请求、跳转一律沿用 4.1。
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

/** 错误摘要里用的中文字段名（只用于展示，键与契约 fieldErrors.field 一致） */
const FIELD_LABELS: Record<string, string> = {
  type: '类型',
  title: '标题',
  description: '详细描述',
  categoryId: '分类',
  location: '地点',
  occurredAt: '发生时间',
  contact: '联系方式',
  publisherName: '联系人称呼'
}

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

/** 字段是否处于校验失败态：控制 aria-invalid 与红边框 */
function isInvalid(field: string): boolean {
  return fieldErrors.value.some((item) => item.field === field)
}

/** 校验失败时把 aria-describedby 指向 FieldError 的 ul（id 约定见 FieldError.vue） */
function describedBy(field: string): string | undefined {
  return isInvalid(field) ? `field-${field}-error` : undefined
}

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

function focusField(field: string): void {
  const target = document.getElementById(`field-${field}`)
  if (!target) return
  target.scrollIntoView({ block: 'center', behavior: 'smooth' })
  target.focus({ preventScroll: true })
}

/**
 * 4.2 修复：网络失败时 fieldErrors 为空数组，原实现直接 errors[0].field 会抛 TypeError，
 * 表现为点了提交什么都没发生（控制台一条未处理异常）。现在空数组直接返回。
 */
function focusFirstError(errors: FieldErrorItem[]): void {
  if (errors.length === 0) return
  focusField(errors[0].field)
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
    <header class="page-head">
      <div>
        <h1 class="page-head__title">发布启事</h1>
        <p class="page-head__desc">带 <span class="field__req">*</span> 的为必填项；描述越具体，语义匹配越准。</p>
      </div>
    </header>

    <form class="panel record-create__form" novalidate @submit.prevent="submit">
      <!-- 校验失败摘要：一条提示告诉用户"有几处、第一处是什么"，每条都能点回字段 -->
      <div v-if="submitted && fieldErrors.length > 0" class="alert alert--danger" role="alert">
        <span class="alert__title">有 {{ fieldErrors.length }} 处需要修改</span>
        <ul class="record-create__summary-list">
          <li v-for="item in fieldErrors" :key="`${item.field}-${item.reason}`">
            <button type="button" class="btn btn--link" @click="focusField(item.field)">
              {{ FIELD_LABELS[item.field] ?? item.field }}：{{ item.reason }}
            </button>
          </li>
        </ul>
      </div>

      <fieldset class="record-create__fieldset" :aria-describedby="describedBy('type')">
        <legend class="field__label">类型 <span class="field__req">*</span></legend>
        <div id="field-type" class="record-create__radios" tabindex="-1">
          <label class="radio">
            <input v-model="form.type" type="radio" name="type" value="LOST" />
            寻物启事（我丢了东西）
          </label>
          <label class="radio">
            <input v-model="form.type" type="radio" name="type" value="FOUND" />
            招领启事（我捡到东西）
          </label>
        </div>
        <FieldError :errors="fieldErrors" field="type" />
      </fieldset>

      <div class="field">
        <label class="field__label" for="field-title">
          标题 <span class="field__req">*</span>
          <span class="field__counter">{{ form.title.length }}/{{ TITLE_MAX }}</span>
        </label>
        <input
          id="field-title"
          v-model="form.title"
          class="control"
          type="text"
          :maxlength="TITLE_MAX"
          placeholder="例：图书馆丢了黑色钱包"
          :aria-invalid="isInvalid('title') ? 'true' : undefined"
          :aria-describedby="describedBy('title')"
        />
        <FieldError :errors="fieldErrors" field="title" />
      </div>

      <div class="field">
        <label class="field__label" for="field-description">
          详细描述 <span class="field__req">*</span>
          <span class="field__counter">{{ form.description.length }}/{{ DESC_MAX }}</span>
        </label>
        <textarea
          id="field-description"
          v-model="form.description"
          class="control"
          rows="5"
          :maxlength="DESC_MAX"
          placeholder="写清丢失/捡到的时间、地点、物品特征，这些都会参与语义匹配"
          :aria-invalid="isInvalid('description') ? 'true' : undefined"
          :aria-describedby="describedBy('description')"
        ></textarea>
        <FieldError :errors="fieldErrors" field="description" />
      </div>

      <div class="record-create__grid">
        <div class="field">
          <label class="field__label" for="field-categoryId">分类</label>
          <select
            id="field-categoryId"
            v-model="form.categoryId"
            class="control"
            :disabled="categories.length === 0"
            :aria-invalid="isInvalid('categoryId') ? 'true' : undefined"
            :aria-describedby="describedBy('categoryId')"
          >
            <option value="">暂不分类</option>
            <option v-for="item in categories" :key="item.id" :value="String(item.id)">
              {{ item.name }}
            </option>
          </select>
          <FieldError :errors="fieldErrors" field="categoryId" />
        </div>

        <div class="field">
          <label class="field__label" for="field-location">
            地点 <span class="field__req">*</span>
          </label>
          <input
            id="field-location"
            v-model="form.location"
            class="control"
            type="text"
            :maxlength="LOCATION_MAX"
            placeholder="例：图书馆三楼自习区"
            :aria-invalid="isInvalid('location') ? 'true' : undefined"
            :aria-describedby="describedBy('location')"
          />
          <FieldError :errors="fieldErrors" field="location" />
        </div>

        <div class="field">
          <label class="field__label" for="field-occurredAt">
            发生时间 <span class="field__req">*</span>
          </label>
          <input
            id="field-occurredAt"
            v-model="form.occurredAt"
            class="control"
            type="datetime-local"
            :aria-invalid="isInvalid('occurredAt') ? 'true' : undefined"
            :aria-describedby="describedBy('occurredAt')"
          />
          <FieldError :errors="fieldErrors" field="occurredAt" />
        </div>

        <div class="field">
          <label class="field__label" for="field-contact">联系方式</label>
          <input
            id="field-contact"
            v-model="form.contact"
            class="control"
            type="text"
            :maxlength="CONTACT_MAX"
            placeholder="手机 / QQ / 微信号"
            :aria-invalid="isInvalid('contact') ? 'true' : undefined"
            :aria-describedby="describedBy('contact')"
          />
          <FieldError :errors="fieldErrors" field="contact" />
        </div>

        <div class="field">
          <label class="field__label" for="field-publisherName">联系人称呼</label>
          <input
            id="field-publisherName"
            v-model="form.publisherName"
            class="control"
            type="text"
            :maxlength="PUBLISHER_MAX"
            placeholder="例：李同学"
            :aria-invalid="isInvalid('publisherName') ? 'true' : undefined"
            :aria-describedby="describedBy('publisherName')"
          />
          <FieldError :errors="fieldErrors" field="publisherName" />
        </div>
      </div>

      <p v-if="categoryHint" class="field__hint">{{ categoryHint }}</p>

      <!-- 非字段级失败（网络/服务端错误）才用页级提示；字段错误由上方摘要与字段红字承担 -->
      <ErrorBanner
        v-if="error && backendErrors.length === 0"
        :error="error"
        title="提交失败"
        retry-label="再试一次"
        @retry="submit"
      />

      <div class="record-create__actions">
        <button
          type="submit"
          class="btn btn--primary btn--lg"
          :disabled="loading"
          :data-loading="loading"
        >
          <span v-if="loading" class="btn__spinner" aria-hidden="true"></span>
          {{ loading ? '提交中…' : '提交启事' }}
        </button>
        <RouterLink :to="{ name: 'RecordList' }" class="btn btn--ghost btn--lg">取消并返回</RouterLink>
      </div>
    </form>
  </section>
</template>

<style scoped>
.record-create__form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  max-width: var(--content-max);
}

.record-create__summary-list {
  flex-basis: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  margin-top: var(--sp-1);
  font-size: var(--fs-xs);
}

/* 用 fieldset/legend 而不是 <label for> 包一组 radio：for 必须指向可标注控件，
   指向 div 是无效 HTML，读屏也读不出这组单选在问什么。这里去掉浏览器默认边框。 */
.record-create__fieldset {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.record-create__fieldset legend {
  display: block; /* legend 上用 flex 会打乱 fieldset 布局，这里回到块级 */
  padding: 0;
}

.record-create__radios {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  padding: var(--sp-1) 0;
}

/* 窄屏两个单选各占一行，宽屏并排；都不换行截断 */
.record-create__radios .radio {
  flex: 1 1 220px;
}

.record-create__grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--sp-4);
}

.record-create__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-3);
  padding-top: var(--sp-2);
}

@media (min-width: 768px) {
  .record-create__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
