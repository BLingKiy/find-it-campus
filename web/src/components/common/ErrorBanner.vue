<script setup lang="ts">
// 错误文案与 requestId 展示一致（基线 §6）：只有这里调 describeError
import { computed } from 'vue'
import { BizError, describeError } from '@/api/http'

const props = defineProps<{ error: unknown }>()

const text = computed(() => describeError(props.error))
const requestId = computed(() => (props.error instanceof BizError ? props.error.requestId : null))
</script>

<template>
  <div class="error-banner" role="alert">
    <span>{{ text }}</span>
    <span v-if="requestId" class="error-banner__id">requestId: {{ requestId }}</span>
  </div>
</template>

<style scoped>
.error-banner {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-md);
  background-color: var(--color-danger-soft);
  color: var(--color-danger);
  font-size: var(--fs-sm);
}

.error-banner__id {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}
</style>
