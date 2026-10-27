// pair 上下文三条入口（契约 §4.13–§4.15）。
// 页面里不出现 fetch；号码只在 getPairContact 的返回值里出现，且调用即产生服务端审计。
import type {
  PairActionResult,
  PairContactView,
  PairStatus,
  PairUnlockResult
} from '@/types/api'
import { request } from './http'

export type PairAction = 'CONTACT' | 'CONFIRM' | 'IGNORE'

/** 查看对方联系方式；HTTP 200 也可能是 available=false（对方尚未公开） */
export function getPairContact(pairId: number): Promise<PairContactView> {
  return request<PairContactView>(`/pairs/${pairId}/contact`)
}

/** 本端公开 / 撤回联系方式；action 缺省为 PUBLISH（契约 §4.15） */
export function unlockPairContact(
  pairId: number,
  action: 'PUBLISH' | 'REVOKE',
  note?: string | null
): Promise<PairUnlockResult> {
  return request<PairUnlockResult>(`/pairs/${pairId}/contact-unlock`, {
    method: 'POST',
    body: { action, ...(action === 'PUBLISH' ? { note: note ?? null } : {}) }
  })
}

/** 只描述这一对内部的进展；不接收 status，MATCHED 由服务端派生 */
export function actOnPair(pairId: number, action: PairAction): Promise<PairActionResult> {
  return request<PairActionResult>(`/pairs/${pairId}`, {
    method: 'PATCH',
    body: { action }
  })
}

/** pair 状态文案集中映射（组件不 switch 枚举值） */
export const PAIR_STATUS_LABELS: Record<PairStatus, string> = {
  NEW: '待联系',
  CONTACTED: '已联系',
  MATCHED: '双方已确认',
  IGNORED: '已忽略'
}
