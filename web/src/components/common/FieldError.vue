<script setup lang="ts">
// 后端 fieldErrors → 输入框下方红字（基线 §6）
import { computed } from 'vue'
import type { FieldErrorItem } from '@/types/api'

const props = defineProps<{ errors: FieldErrorItem[]; field: string }>()

const messages = computed(() =>
  props.errors.filter((item) => item.field === props.field).map((item) => item.reason)
)
</script>

<template>
  <ul v-if="messages.length" class="field-error">
    <li v-for="message in messages" :key="message">{{ message }}</li>
  </ul>
</template>

<style scoped>
.field-error {
  margin-top: var(--sp-1);
  color: var(--color-danger);
  font-size: var(--fs-xs);
}
</style>
