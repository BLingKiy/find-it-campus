<script setup lang="ts">
// 本人启事的管理入口（C2）：编辑 / 关闭。
// 只有 isMine=true 才渲染 —— 判断依据是服务端给的 publisher_id 结果，不是前端比对展示名；
// 越权拦截在服务端（非所有者写接口 403/404），前端隐藏只是可用性优化。
// 关闭是不可逆的终态操作，走两段式确认（按钮 → 确认条），不用原生 confirm（移动端弹窗不可控）。
import { computed, ref } from 'vue'
import { patchStatus } from '@/api/records'
import { useRequest } from '@/composables/useRequest'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import type { RecordStatus } from '@/types/api'

const props = withDefaults(
  defineProps<{
    recordId: number
    status: RecordStatus
    isMine: boolean
    /** card：列表卡片内联一行；block：详情页操作区 */
    variant?: 'card' | 'block'
  }>(),
  { variant: 'block' }
)

const emit = defineEmits<{
  /** 状态已改变（关闭成功）：由父组件决定刷新列表还是跳「我的启事」 */
  changed: [payload: { id: number; status: RecordStatus }]
}>()

const confirming = ref(false)
const { error, loading, run } = useRequest(patchStatus, { immediate: false })

const canManage = computed(() => props.isMine && props.status === 'PENDING')
const terminalHint = computed(() => {
  if (props.status === 'COMPLETED') return '这条启事已完成，不能再编辑或关闭。'
  if (props.status === 'CLOSED') return '这条启事已关闭，不能再编辑。'
  return ''
})

async function confirmClose(): Promise<void> {
  const outcome = await run(props.recordId, 'CLOSED')
  if (!outcome) return
  confirming.value = false
  emit('changed', { id: outcome.id, status: outcome.status })
}
</script>

<template>
  <div
    v-if="isMine"
    class="own-actions"
    :class="`own-actions--${variant}`"
    data-testid="own-record-actions"
  >
    <template v-if="canManage">
      <RouterLink
        class="btn btn--sm btn--ghost"
        :to="{ name: 'RecordEdit', params: { id: recordId } }"
        data-testid="action-edit"
      >
        编辑
      </RouterLink>

      <template v-if="!confirming">
        <button
          type="button"
          class="btn btn--sm btn--danger"
          data-testid="action-close"
          @click="confirming = true"
        >
          关闭
        </button>
      </template>
      <template v-else>
        <span class="own-actions__confirm" role="status">关闭后不再公开展示，且不可恢复</span>
        <button
          type="button"
          class="btn btn--sm btn--danger"
          :disabled="loading"
          data-testid="action-close-confirm"
          @click="confirmClose"
        >
          <span v-if="loading" class="btn__spinner" aria-hidden="true"></span>
          确认关闭
        </button>
        <button
          type="button"
          class="btn btn--sm btn--ghost"
          :disabled="loading"
          @click="confirming = false"
        >
          取消
        </button>
      </template>
    </template>

    <p v-else class="hint">{{ terminalHint }}</p>

    <ErrorBanner
      v-if="error"
      class="own-actions__error"
      :error="error"
      title="操作失败"
    />
  </div>
</template>

<style scoped>
.own-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
}

.own-actions--card {
  padding-top: var(--sp-2);
  border-top: 1px dashed var(--color-border);
}

.own-actions__confirm {
  color: var(--color-danger);
  font-size: var(--fs-xs);
}

.own-actions__error {
  flex-basis: 100%;
}
</style>
