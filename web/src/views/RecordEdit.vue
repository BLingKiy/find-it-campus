<script setup lang="ts">
// 编辑已发布的启事（C2）：本人 + PENDING 才允许进入真正的表单。
// 权限与状态在服务端也会再判一次（非所有者 403/404、终态 409），
// 前端提前拦是为了不留一个"填完才报错"的空欢喜流程。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRecord, updateRecord } from '@/api/records'
import { loadCategories } from '@/api/categoryCache'
import { BizError } from '@/api/http'
import RecordForm from '@/components/record/RecordForm.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import LoadingBlock from '@/components/common/LoadingBlock.vue'
import { useRequest } from '@/composables/useRequest'
import type { CategoryItem, RecordCreatePayload, RecordDetail } from '@/types/api'
import type { RecordFormState } from '@/types/domain'

const route = useRoute()
const router = useRouter()

const recordId = computed(() => Number(route.params.id))
const isValidId = computed(() => Number.isInteger(recordId.value) && recordId.value > 0)

const { data: record, error: loadError, loading: loadLoading, run: load } = useRequest(
  getRecord,
  { immediate: false }
)
const { error: saveError, loading: saveLoading, run: save } = useRequest(updateRecord, {
  immediate: false
})

if (isValidId.value) void load(recordId.value)

const categories = ref<CategoryItem[]>([])
const categoryHint = ref('')
const formRef = ref<InstanceType<typeof RecordForm> | null>(null)

const loadNotFound = computed(
  () => loadError.value instanceof BizError && loadError.value.code === 20001
)
const notOwner = computed(() => record.value !== null && !record.value.isMine)
const notEditable = computed(
  () => record.value !== null && record.value.isMine && record.value.status !== 'PENDING'
)

/** 契约 §1 的时间格式 → datetime-local 的值（"2026-10-21 15:20:00" → "2026-10-21T15:20"） */
function toLocalInput(apiDateTime: string): string {
  return apiDateTime.slice(0, 16).replace(' ', 'T')
}

const initial = computed<Partial<RecordFormState> | undefined>(() => {
  const current: RecordDetail | null = record.value
  if (!current) return undefined
  return {
    type: current.type,
    title: current.title,
    description: current.description,
    categoryId: current.categoryId === null ? '' : String(current.categoryId),
    location: current.location,
    occurredAt: toLocalInput(current.occurredAt),
    // 本人视角才是原文；非本人这一页本来就不给填
    contact: current.contact ?? '',
    publisherName: current.publisherName ?? ''
  }
})

const backendErrors = computed(() => (saveError.value instanceof BizError ? saveError.value.fieldErrors : []))

async function submit(payload: RecordCreatePayload): Promise<void> {
  const updated = await save(recordId.value, payload)
  if (!updated) {
    formRef.value?.focusBackendErrors()
    return
  }
  void router.replace({
    name: 'RecordDetail',
    params: { id: updated.id },
    query: { updated: '1' }
  })
}

onMounted(async () => {
  try {
    categories.value = await loadCategories()
  } catch {
    categoryHint.value = '分类加载失败，可以先保留原分类提交'
  }
})
</script>

<template>
  <section class="record-edit">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">编辑启事</h1>
        <p class="page-head__desc">只有发布者本人可以修改，且只能改「进行中」的启事。</p>
      </div>
      <div class="page-head__actions">
        <RouterLink :to="{ name: 'MyRecords' }" class="btn btn--ghost">← 我的启事</RouterLink>
      </div>
    </header>

    <LoadingBlock v-if="loadLoading" variant="detail" :rows="1" label="正在加载启事" />

    <EmptyState
      v-else-if="loadNotFound"
      title="记录不存在或不可查看"
      description="这条启事可能已被删除或关闭，或链接里的编号不对"
      action-label="回到启事列表"
      :to="{ name: 'RecordList' }"
    />

    <EmptyState
      v-else-if="notOwner"
      title="只有发布者本人可以编辑"
      description="你正在查看别人的启事；编辑入口只对发布者显示"
      action-label="回到启事列表"
      :to="{ name: 'RecordList' }"
    />

    <EmptyState
      v-else-if="notEditable"
      title="这条启事已不可编辑"
      description="已完成或已关闭的启事不能再修改内容"
      action-label="回到我的启事"
      :to="{ name: 'MyRecords' }"
    />

    <ErrorBanner
      v-else-if="loadError"
      :error="loadError"
      retry-label="重试"
      @retry="load(recordId)"
    />

    <RecordForm
      v-else-if="record && initial"
      ref="formRef"
      mode="edit"
      :initial="initial"
      :loading="saveLoading"
      :error="saveError"
      :backend-field-errors="backendErrors"
      :categories="categories"
      :category-hint="categoryHint"
      @submit="submit"
    />
  </section>
</template>
