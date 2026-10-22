<script setup lang="ts">
// 兜底路由：一行文案 + 回首页（基线 §2）
import EmptyState from '@/components/common/EmptyState.vue'
</script>

<template>
  <div class="not-found">
    <EmptyState title="404 · 页面不存在" description="你访问的地址没有对应的页面" />
    <p class="not-found__back">
      <RouterLink :to="{ name: 'RecordList' }">返回首页</RouterLink>
    </p>
  </div>
</template>

<style scoped>
.not-found__back {
  margin-top: var(--sp-4);
  text-align: center;
  font-size: var(--fs-sm);
}
</style>
