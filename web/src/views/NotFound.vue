<script setup lang="ts">
// 兜底路由（基线 §2）：空态 + 回首页。4.2 起与其它空态共用 EmptyState，404 不再另有一套视觉。
import { useRoute } from 'vue-router'
import EmptyState from '@/components/common/EmptyState.vue'

const route = useRoute()
</script>

<template>
  <section class="not-found">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">页面不存在</h1>
        <p class="page-head__desc">链接可能输错了，或这个地址已经被改掉。</p>
      </div>
    </header>

    <EmptyState
      title="404 · 没有这个页面"
      :description="`当前地址：${route.fullPath}`"
      action-label="回到启事列表"
      :to="{ name: 'RecordList' }"
    />
  </section>
</template>

<style scoped>
.not-found {
  max-width: var(--content-max);
}
</style>
