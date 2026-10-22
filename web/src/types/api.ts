// 与 docs/api-contract.md 逐字段对齐；改字段须先改契约，三处同 commit（契约 §11）
// 后端只回枚举原值，中文文案由前端映射（components/record/*Tag.vue）

export type RecordType = 'LOST' | 'FOUND'
export type RecordStatus = 'PENDING' | 'CLAIMED' | 'CLOSED'
export type SortBy = 'createdAt' | 'occurredAt'
export type SortOrder = 'asc' | 'desc'
export type MatchEngineKind = 'LOCAL_KEYWORD' | 'EXTERNAL_EMBEDDING'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T | null
  requestId: string
}

export interface PageResult<T> {
  page: number
  pageSize: number
  total: number
  totalPages: number
  hasNext: boolean
  list: T[]
}

/** GET /api/records 列表项：刻意不含 description / contact / publisherName（契约 §3.2） */
export interface RecordListItem {
  id: number
  type: RecordType
  status: RecordStatus
  title: string
  categoryId: number | null
  categoryName: string | null
  location: string
  occurredAt: string
  createdAt: string
}

export interface RecordDetail extends RecordListItem {
  description: string
  contact: string | null
  publisherName: string | null
  updatedAt: string
  allowedStatuses: RecordStatus[]
}

export interface RecordCreatePayload {
  type: RecordType
  title: string
  description: string
  categoryId?: number | null
  location: string
  occurredAt: string
  contact?: string | null
  publisherName?: string | null
}

export interface RecordQuery {
  type?: RecordType
  status?: RecordStatus | 'ALL'
  categoryId?: number
  keyword?: string
  page?: number
  pageSize?: number
  sortBy?: SortBy
  sortOrder?: SortOrder
}

export interface MatchPayload {
  text: string
  type?: RecordType
  categoryId?: number
  topN?: number
  minScore?: number
  excludeRecordId?: number | null
}

export interface MatchItem {
  recordId: number
  score: number
  scorePercent: number
  reason: string
  type: RecordType
  status: RecordStatus
  title: string
  categoryName: string | null
  location: string
  occurredAt: string
  createdAt: string
}

export interface MatchResult {
  matchId: number
  engine: MatchEngineKind
  inputType: RecordType | null
  topN: number
  minScore: number
  tookMs: number
  total: number
  items: MatchItem[]
}

export interface CategoryItem {
  id: number
  code: string
  name: string
  sortOrder: number
  active: boolean
}

export interface FieldErrorItem {
  field: string
  reason: string
}
