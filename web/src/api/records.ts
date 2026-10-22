import type {
  PageResult,
  RecordCreatePayload,
  RecordDetail,
  RecordListItem,
  RecordQuery,
  RecordStatus
} from '@/types/api'
import { mockRecordPage } from '@/mock/records'
import { request } from './http'

// TODO(4.1): mock 与开关分支在 ZHYYKFX-8（4.1 页面接通后端）后删除；
// 若 4.1 决定保留开关供答辩演示，须在 4.1 明确写出保留理由。
// 字符串比较：未设置或 =false 一律走真实接口。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

/** GET /api/records（契约 §3） */
export function listRecords(query: RecordQuery = {}): Promise<PageResult<RecordListItem>> {
  if (USE_MOCK) return mockRecordPage(query)
  // baseURL 已含 /api，故路径为 /records
  return request<PageResult<RecordListItem>>('/records', { query: { ...query } })
}

/** GET /api/records/{id}（契约 §4） */
export function getRecord(id: number): Promise<RecordDetail> {
  return request<RecordDetail>(`/records/${id}`)
}

/** POST /api/records（契约 §5） */
export function createRecord(payload: RecordCreatePayload): Promise<RecordDetail> {
  return request<RecordDetail>('/records', { method: 'POST', body: payload })
}

/** PUT /api/records/{id}（契约 §6，全量语义） */
export function updateRecord(id: number, payload: RecordCreatePayload): Promise<RecordDetail> {
  return request<RecordDetail>(`/records/${id}`, { method: 'PUT', body: payload })
}

/** PATCH /api/records/{id}/status（契约 §6.1，只发一个字段） */
export function patchStatus(id: number, status: RecordStatus): Promise<{
  id: number
  status: RecordStatus
  allowedStatuses: RecordStatus[]
}> {
  return request(`/records/${id}/status`, { method: 'PATCH', body: { status } })
}

/** DELETE /api/records/{id}（契约 §6.2，逻辑删除，data 恒为 null） */
export function deleteRecord(id: number): Promise<null> {
  return request<null>(`/records/${id}`, { method: 'DELETE' })
}
