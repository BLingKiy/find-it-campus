// 与 docs/api-contract.md 逐字段对齐；改字段须先改契约，三处同 commit（契约 §11）
// 后端只回枚举原值，中文文案由前端映射（components/record/*Tag.vue）

export type RecordType = 'LOST' | 'FOUND'
/** v2 三态（契约 §1）：CLAIMED 已作废，后端收到即 10001 */
export type RecordStatus = 'PENDING' | 'COMPLETED' | 'CLOSED'
export type SortBy = 'createdAt' | 'occurredAt'
export type SortOrder = 'asc' | 'desc'
export type MatchEngineKind = 'LOCAL_KEYWORD' | 'EXTERNAL_EMBEDDING'

/** v2 pair 四态（契约 §4.13）：不存在「已解决」，MATCHED 只由两端确认派生 */
export type PairStatus = 'NEW' | 'CONTACTED' | 'MATCHED' | 'IGNORED'
/** 联系方式档位（契约 §5.1）：<0.75 不出卡片，故这里只有两档 */
export type PairTier = 'DIRECT' | 'AUTHORIZABLE'
export type PairSide = 'LOST' | 'FOUND'
export type PairEngine = 'DEEPSEEK' | 'LOCAL_KEYWORD'

/** PairCard 的对端摘要（契约 §5.1）：title/location 已由服务端打码 */
export interface PairCounterpart {
  recordId: number
  type: RecordType
  title: string
  location: string
  occurredAt: string
  createdAt: string
  status: RecordStatus
}

/**
 * 详情页内嵌的配对卡片（契约 §5.1）。
 * 永远不含 contact：号码只能从 GET /api/pairs/{id}/contact 取，卡片只给按钮状态。
 */
export interface PairCard {
  pairId: number
  score: number
  scorePercent: number
  tier: PairTier
  status: PairStatus
  matched: boolean
  myConfirmed: boolean
  otherConfirmed: boolean
  lostConfirmedAt: string | null
  foundConfirmedAt: string | null
  engine: PairEngine
  degraded: boolean
  counterpart: PairCounterpart
  mySide: PairSide
  myUnlocked: boolean
  otherUnlocked: boolean
  myNote: string | null
  otherNote: string | null
  contactAvailable: boolean
  contactHint: string
}

/** GET /api/pairs/{id}/contact（契约 §4.14）：available=false 是业务状态，不是错误 */
export interface PairContactView {
  pairId: number
  score: number
  scorePercent: number
  tier: PairTier
  viewerSide: PairSide
  targetSide: PairSide
  available: boolean
  contact: string | null
  reason: 'NOT_PUBLIC' | 'NOT_PROVIDED' | null
  message: string
  unlockedAt: string | null
  note: string | null
}

/** POST /api/pairs/{id}/contact-unlock（契约 §4.15） */
export interface PairUnlockResult {
  pairId: number
  side: PairSide
  public: boolean
  unlockedAt: string | null
  note: string | null
}

/** PATCH /api/pairs/{id}（契约 §4.13） */
export interface PairActionResult {
  pairId: number
  status: PairStatus
  matched: boolean
  mySide: PairSide
  myConfirmed: boolean
  otherConfirmed: boolean
  lostConfirmedAt: string | null
  foundConfirmedAt: string | null
  updatedAt: string
}

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
  /** 非本人行已被服务端打码；本人行是原文 */
  title: string
  categoryId: number | null
  categoryName: string | null
  /** 非本人行已被服务端截断（末尾带 …） */
  location: string
  occurredAt: string
  createdAt: string
  /**
   * v2 所有权标记：判断依据是 publisher_id，不是展示名。
   * 前端只据它决定是否渲染「编辑 / 关闭」入口 —— 真正的越权拦截在服务端。
   */
  isMine: boolean
}

export interface RecordDetail extends RecordListItem {
  description: string
  /** 非所有者一律 null（服务端整体置空，不做 138****0001 类部分掩码） */
  contact: string | null
  /** 非所有者已被服务端脱敏为「林**」；空值兜底「匿名同学」 */
  publisherName: string | null
  /** 完成时间只对所有者返回；非所有者与未完成记录均为 null */
  completedAt: string | null
  updatedAt: string
  /** 服务端状态机给出；非所有者恒为空数组 */
  allowedStatuses: RecordStatus[]
  /** 是否已有有效 MATCHED pair：决定「标记完成」能不能点（服务端同一判断） */
  hasMatchedPair: boolean
  matchedPairCount: number
  pairState: string | null
  lowConfidenceCount: number
  /** 仅 isMine=true 时返回（契约 §4.2）；非所有者字段省略，不是空数组 */
  pairs?: PairCard[]
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

/** GET /api/auth/me、POST /api/auth/login 的 data（契约 §4.9/§4.11，只有三个字段） */
export interface UserInfo {
  id: number
  studentNo: string
  displayName: string
}

/** POST /api/auth/login 请求体（契约 §4.9） */
export interface LoginPayload {
  studentNo: string
  password: string
}
