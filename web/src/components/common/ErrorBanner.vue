<script setup lang="ts">
// 错误态唯一出口（基线 §6 / ui-baseline §3.5）：
//   文案由 describeError 统一给（组件里不 switch 错误码）
//   可重试的请求把 retry 事件接上，用户看到错误后能就地再试一次
import { computed } from 'vue'
import { BizError, describeError } from '@/api/http'

const props = defineProps<{
  error: unknown
  /** 默认按"加载失败"表述；提交类接口传"提交失败" */
  title?: string
  /** 传了就显示重试按钮（父组件据此重发同一个请求） */
  retryLabel?: string
}>()

const emit = defineEmits<{ retry: [] }>()

const text = computed(() => describeError(props.error))
const requestId = computed(() => (props.error instanceof BizError ? props.error.requestId : null))
</script>

<template>
  <div class="alert alert--danger" role="alert">
    <span class="alert__title">{{ title ?? '加载失败' }}</span>
    <span class="alert__text">{{ text }}</span>
    <span v-if="requestId" class="alert__meta">requestId {{ requestId }}</span>
    <button
      v-if="retryLabel"
      type="button"
      class="btn btn--sm btn--danger alert__action"
      @click="emit('retry')"
    >
      {{ retryLabel }}
    </button>
  </div>
</template>
