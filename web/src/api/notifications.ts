// 通知两条接口（契约 §4.16 / §4.17）。body 已由服务端打码，前端只负责展示。
import type { NotificationPage, NotificationReadResult } from '@/types/api'
import { request } from './http'

/** GET /api/notifications：分页 + 全部未读数 */
export function listNotifications(
  query: { page?: number; pageSize?: number } = {}
): Promise<NotificationPage> {
  return request<NotificationPage>('/notifications', { query: { ...query } })
}

/** PATCH /api/notifications/{id}/read：返回更新后的 unreadCount，用于同步顶栏红点 */
export function markNotificationRead(id: number): Promise<NotificationReadResult> {
  return request<NotificationReadResult>(`/notifications/${id}/read`, { method: 'PATCH' })
}
