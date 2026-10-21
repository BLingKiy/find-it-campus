<script setup lang="ts">
// 详情页占位：挂载时校验 id 是正整数，否则跳 NotFound（基线 §2）
// 这样手输 /records/abc 不会带着非数字 id 去打接口
import { computed, onBeforeMount } from 'vue'
import { useRoute, useRouter } from 'vue-router'

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
</script>

<template>
  <section v-if="isValidId" class="record-detail">
    <h1 class="record-detail__title">启事详情 #{{ recordId }}</h1>
    <p class="record-detail__hint">
      详情页占位。正文、联系方式、状态操作按钮与「帮我找找相关启事」匹配区由 4.1 实现。
    </p>
    <RouterLink :to="{ name: 'RecordList' }" class="record-detail__back">← 返回列表</RouterLink>
  </section>
</template>

<style scoped>
.record-detail__title {
  font-size: var(--fs-xxl);
  font-weight: var(--fw-bold);
  line-height: var(--lh-tight);
}

.record-detail__hint {
  margin-top: var(--sp-3);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.record-detail__back {
  display: inline-block;
  margin-top: var(--sp-5);
  font-size: var(--fs-sm);
}
</style>
