<script setup lang="ts">
// 提交页（基线 §2）：表单与校验在 RecordForm（C2 抽出，发 / 编辑共用），本页只管
// 「加载分类 → 提交 → 跳详情」。字段级校验与后端 fieldErrors 合并逻辑不在这里重复。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createRecord } from '@/api/records'
import { loadCategories } from '@/api/categoryCache'
import { BizError } from '@/api/http'
import RecordForm from '@/components/record/RecordForm.vue'
import { useRequest } from '@/composables/useRequest'
import type { CategoryItem, RecordCreatePayload, RecordType } from '@/types/api'

const route = useRoute()
const router = useRouter()

const presetType = computed<RecordType>(() => {
  const raw = route.query.type
  return raw === 'LOST' || raw === 'FOUND' ? raw : 'LOST'
})

const initial = computed(() => ({ type: presetType.value }))

const categories = ref<CategoryItem[]>([])
const categoryHint = ref('')
const formRef = ref<InstanceType<typeof RecordForm> | null>(null)

const { error, loading, run } = useRequest(createRecord, { immediate: false })
const backendErrors = computed(() => (error.value instanceof BizError ? error.value.fieldErrors : []))

async function submit(payload: RecordCreatePayload): Promise<void> {
  const created = await run(payload)
  if (!created) {
    formRef.value?.focusBackendErrors()
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
</script>

<template>
  <section class="record-create">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">发布启事</h1>
        <p class="page-head__desc">
          带 <span class="field__req">*</span> 的为必填项；描述越具体，语义匹配越准。
        </p>
      </div>
    </header>

    <RecordForm
      ref="formRef"
      mode="create"
      :initial="initial"
      :loading="loading"
      :error="error"
      :backend-field-errors="backendErrors"
      :categories="categories"
      :category-hint="categoryHint"
      @submit="submit"
    />
  </section>
</template>
