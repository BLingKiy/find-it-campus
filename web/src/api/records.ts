import type {
  PageResult,
  RecordCreatePayload,
  RecordDetail,
  RecordListItem,
  RecordQuery,
  RecordStatus
} from '@/types/api'
import { request } from './http'

/** GET /api/records（契约 §3） */
export function listRecords(query: RecordQuery = {}): Promise<PageResult<RecordListItem>> {
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
