// 顶栏未读红点的单一状态源（契约 §4.16 / §4.17）。
// 不引 Pinia：模块级 ref + 在途请求去重，通知页标记已读后用服务端返回的 unreadCount 直接回写。
import { readonly, ref } from 'vue'
import { listNotifications } from '@/api/notifications'

const unreadCount = ref(0)
let inflight: Promise<number> | null = null

/** 拉一次「全部未读数」；并发调用合并成一条请求。失败时保留旧值，由调用方决定是否提示。 */
async function refreshUnread(): Promise<number> {
  if (inflight) return inflight
  inflight = (async () => {
    try {
      // pageSize=1：只需要 data.unreadCount，不关心本页内容
      const page = await listNotifications({ page: 1, pageSize: 1 })
      unreadCount.value = Math.max(0, page.unreadCount)
      return unreadCount.value
    } finally {
      inflight = null
    }
  })()
  return inflight
}

/** 服务端已给出准确未读数时直接写，不再多打一次列表接口 */
function setUnread(count: number): void {
  unreadCount.value = Math.max(0, Math.floor(count))
}

/** 登出 / 未登录：红点归零，避免把上一个会话的数字带给下一个人 */
function resetUnread(): void {
  unreadCount.value = 0
}

export function useNotificationBadge() {
  return {
    unreadCount: readonly(unreadCount),
    refreshUnread,
    setUnread,
    resetUnread
  }
}
