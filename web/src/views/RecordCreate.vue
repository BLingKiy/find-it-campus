<script setup lang="ts">
// 提交页占位：表单控件、校验与 FieldError 落位由 4.1 实现（基线 §2）
</script>

<template>
  <section class="record-create">
    <h1 class="record-create__title">发布启事</h1>
    <p class="record-create__hint">
      提交页占位。类型 / 标题 / 描述 / 分类 / 地点 / 时间 / 联系方式字段由 4.1 实现。
    </p>
    <RouterLink :to="{ name: 'RecordList' }" class="record-create__back">← 返回列表</RouterLink>
  </section>
</template>

<style scoped>
.record-create__title {
  font-size: var(--fs-xxl);
  font-weight: var(--fw-bold);
  line-height: var(--lh-tight);
}

.record-create__hint {
  margin-top: var(--sp-3);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.record-create__back {
  display: inline-block;
  margin-top: var(--sp-5);
  font-size: var(--fs-sm);
}
</style>
