// 「我的启事」唯一请求出口（契约 §4.12）。服务端按 session 过滤，前端不传用户 id。
import type { MyRecordItem, MyRecordQuery, PageResult } from '@/types/api'
import { request } from './http'

/** GET /api/my/records：三态都返回，pairs[] 为所有者视角的逐条配对 */
export function listMyRecords(query: MyRecordQuery = {}): Promise<PageResult<MyRecordItem>> {
  return request<PageResult<MyRecordItem>>('/my/records', { query: { ...query } })
}
