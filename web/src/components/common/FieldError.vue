<script setup lang="ts">
// 字段级错误（基线 §6 / ui-baseline §3.2）。
// id 固定为 field-{field}-error，调用方用 aria-describedby 指过来，
// 控件再挂 aria-invalid="true" —— 读屏聚焦时会把这条错误一起读出来。
// 视觉（红字 + "!" 圆点）来自 styles/components.css 的 .field-error，组件不重复定义。
import { computed } from 'vue'
import type { FieldErrorItem } from '@/types/api'

const props = defineProps<{ errors: FieldErrorItem[]; field: string }>()

const messages = computed(() =>
  props.errors.filter((item) => item.field === props.field).map((item) => item.reason)
)
</script>

<template>
  <ul v-if="messages.length" :id="`field-${field}-error`" class="field-error">
    <li v-for="message in messages" :key="message">{{ message }}</li>
  </ul>
</template>
