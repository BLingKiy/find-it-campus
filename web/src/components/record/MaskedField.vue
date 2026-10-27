<script setup lang="ts">
// 遮蔽占位（C2）：不渲染任何号码片段，只渲染「登录后可查看」这一种占位。
// 真正的隐私边界在服务端（非所有者 contact=null、描述打码、地点截断）；
// 这里负责"页面上也没有半截号码可抄"，并要求 DOM 里 1[3-9]\d{9} 0 命中。
withDefaults(
  defineProps<{
    /** inline：字段内占位（联系方式/地点）；block：整块占位（描述/匹配区） */
    variant?: 'inline' | 'block'
    /** block 的标题，默认「登录后可查看」 */
    title?: string
    /** block 的补充说明 */
    description?: string
    /** 有 to 时渲染为登录引导链接（带 redirect 回跳） */
    to?: string | Record<string, unknown>
    actionLabel?: string
    /** 悬停/读屏补充说明（例如"仅发布者本人及已确认配对可见"） */
    hint?: string
  }>(),
  { variant: 'inline' }
)
</script>

<template>
  <span
    v-if="variant === 'inline'"
    class="masked masked--inline"
    data-testid="masked-placeholder"
    data-masked="true"
    :title="hint"
  >
    登录后可查看
  </span>

  <div v-else class="masked masked--block" data-testid="masked-placeholder" data-masked="true">
    <span class="masked__title">{{ title ?? '登录后可查看' }}</span>
    <span v-if="description" class="masked__desc">{{ description }}</span>
    <RouterLink
      v-if="to && actionLabel"
      class="btn btn--ghost btn--sm masked__action"
      :to="to"
    >
      {{ actionLabel }}
    </RouterLink>
  </div>
</template>
