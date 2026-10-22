<script setup lang="ts">
// 空态只有这一个形态（基线 §6 / ui-baseline §3.6）。
// 三档用法：
//   纯说明        <EmptyState title="…" description="…" />
//   说明 + 建议   <EmptyState title="…" action-text="点上方「重置」…" />
//   说明 + 动作   <EmptyState title="…" :to="{name:'RecordCreate'}" action-label="去发布" />
// 空态必须教用户下一步做什么，所以动作优先于纯文案（后者只在动作不在本区时使用）。
defineProps<{
  title: string
  description?: string
  /** 纯文案建议，不产生交互（动作在别处时用） */
  actionText?: string
  /** 有 to 时渲染成按钮式链接；没有 to 时忽略 */
  actionLabel?: string
  to?: string | Record<string, unknown>
}>()
</script>

<template>
  <div class="empty">
    <p class="empty__title">{{ title }}</p>
    <p v-if="description" class="empty__desc">{{ description }}</p>
    <p v-if="actionText && !to" class="empty__desc">{{ actionText }}</p>
    <RouterLink v-if="to && actionLabel" class="btn btn--ghost empty__action" :to="to">
      {{ actionLabel }}
    </RouterLink>
  </div>
</template>
