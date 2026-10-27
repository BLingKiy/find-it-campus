// TODO(4.1): 临时 mock 数据层（后端 3.1 尚未可调用，用于页面联调与演示）；
// ZHYYKFX-6（3.1 后端接口）就绪后删除本目录，届时 http.ts 的 USE_MOCK 分支一并删除。
// 只被 mock/server.ts 引用；页面与 api/*.ts 不得 import 本目录任何文件。
import type {
  CategoryItem,
  FieldErrorItem,
  MatchPayload,
  MatchResult,
  PageResult,
  RecordCreatePayload,
  RecordDetail,
  RecordListItem,
  RecordQuery,
  RecordStatus
} from '@/types/api'

/** 状态流转表（契约 §6.1）：模拟后端 RecordStatusPolicy，前端不自行推导 */
const TRANSITIONS: Record<RecordStatus, RecordStatus[]> = {
  PENDING: ['CLAIMED', 'CLOSED'],
  CLAIMED: ['CLOSED'],
  CLOSED: []
}

function allowedStatuses(status: RecordStatus): RecordStatus[] {
  return [...TRANSITIONS[status]]
}

/** Contract §1 时间格式：yyyy-MM-dd HH:mm:ss */
function formatDateTime(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

export const MOCK_CATEGORIES: CategoryItem[] = [
  { id: 1, code: 'WALLET', name: '钱包卡证', sortOrder: 10, active: true },
  { id: 2, code: 'PHONE', name: '手机数码', sortOrder: 20, active: true },
  { id: 3, code: 'KEYS', name: '钥匙', sortOrder: 30, active: true },
  { id: 4, code: 'DOCUMENT', name: '证件资料', sortOrder: 40, active: true },
  { id: 5, code: 'BAG', name: '包袋', sortOrder: 50, active: true },
  { id: 6, code: 'CLOTHING', name: '衣物饰品', sortOrder: 60, active: true },
  { id: 7, code: 'STATIONERY', name: '书籍文具', sortOrder: 70, active: true },
  { id: 8, code: 'OTHER', name: '其他', sortOrder: 90, active: true },
  // 停用分类：onlyActive=false 时才返回，详情页显示历史记录自带的 categoryName
  { id: 9, code: 'SPORTS', name: '运动器材', sortOrder: 80, active: false }
]

type MockRecord = RecordDetail & { deleted: boolean }

/** 演示期用 sessionStorage 续命：整页刷新后仍能看到刚创建的记录（仅本标签页）。 */
const STORAGE_KEY = 'find-it-campus:mock-records:v1'
const SEED_NEXT_ID = 27

const SEED_RECORDS: MockRecord[] = [
  {
    id: 26,
    type: 'FOUND',
    status: 'PENDING',
    title: '捡到一张浅蓝色卡片',
    description: '大学生活动中心公告栏下捡到一张卡片，上面姓名被涂改看不清，愿意还的同学请联系我核对信息。',
    categoryId: null,
    categoryName: null,
    location: '大学生活动中心公告栏',
    occurredAt: '2026-10-21 13:30:00',
    contact: '13800000026',
    publisherName: '陈同学',
    allowedStatuses: allowedStatuses('PENDING'),
    createdAt: '2026-10-21 13:50:00',
    updatedAt: '2026-10-21 13:50:00',
    deleted: false
  },
  {
    id: 15,
    type: 'FOUND',
    status: 'PENDING',
    title: '教室捡到卡套内有学生证',
    description: '第三教学楼 201 第四排桌上捡到一个透明卡套，里面有学生证和一张饭卡，请失主来认领。',
    categoryId: 4,
    categoryName: '证件资料',
    location: '第三教学楼201',
    occurredAt: '2026-10-21 12:10:00',
    contact: '13800000015',
    publisherName: '王同学',
    allowedStatuses: allowedStatuses('PENDING'),
    createdAt: '2026-10-21 12:30:00',
    updatedAt: '2026-10-21 12:30:00',
    deleted: false
  },
  {
    id: 13,
    type: 'FOUND',
    status: 'PENDING',
    title: '图书馆拾到黑色钱包',
    description: '图书馆三楼自习区靠窗座位捡到一个黑色皮钱包，内有两张银行卡和若干现金，已交失物招领处旁的值班台。',
    categoryId: 1,
    categoryName: '钱包卡证',
    location: '图书馆三楼自习区',
    occurredAt: '2026-10-20 15:00:00',
    contact: '13800000013',
    publisherName: '周同学',
    allowedStatuses: allowedStatuses('PENDING'),
    createdAt: '2026-10-20 15:30:00',
    updatedAt: '2026-10-20 15:30:00',
    deleted: false
  },
  {
    id: 1,
    type: 'LOST',
    status: 'PENDING',
    title: '图书馆丢了黑色钱包',
    description:
      '昨天下午在图书馆三楼自习区靠窗的位置看书，起身走的时候发现黑色皮质钱包不见了，里面有两张银行卡和三百多块现金，卡是工行的，非常着急，捡到的同学联系我，必有感谢。',
    categoryId: 1,
    categoryName: '钱包卡证',
    location: '图书馆三楼自习区',
    occurredAt: '2026-10-19 17:30:00',
    contact: '13800000001',
    publisherName: '李同学',
    allowedStatuses: allowedStatuses('PENDING'),
    createdAt: '2026-10-20 09:10:00',
    updatedAt: '2026-10-20 09:10:00',
    deleted: false
  },
  {
    id: 9,
    type: 'LOST',
    status: 'CLAIMED',
    title: '丢了一个灰色双肩背包',
    description: '第一教学楼 305 下课后落在座位上，灰色双肩背包，侧面有个水杯袋，里面有一本线性代数课本。',
    categoryId: 5,
    categoryName: '包袋',
    location: '第一教学楼305',
    occurredAt: '2026-10-19 15:20:00',
    contact: '13800000009',
    publisherName: '张同学',
    allowedStatuses: allowedStatuses('CLAIMED'),
    createdAt: '2026-10-19 16:05:00',
    updatedAt: '2026-10-19 18:20:00',
    deleted: false
  },
  {
    id: 7,
    type: 'LOST',
    status: 'PENDING',
    title: '遗落一副银色圆框眼镜',
    description: '第二教学楼阶梯教室 A 上课后遗落一副银色圆框眼镜，装在灰色绒布袋里，度数不高但急需使用。',
    categoryId: 6,
    categoryName: '衣物饰品',
    location: '第二教学楼阶梯教室A',
    occurredAt: '2026-10-18 19:40:00',
    contact: '13800000007',
    publisherName: '刘同学',
    allowedStatuses: allowedStatuses('PENDING'),
    createdAt: '2026-10-18 21:02:00',
    updatedAt: '2026-10-18 21:02:00',
    deleted: false
  },
  {
    id: 23,
    type: 'FOUND',
    status: 'CLAIMED',
    title: '食堂捡到一串钥匙挂小熊挂件',
    description: '第二食堂二楼餐桌上捡到一串钥匙，挂着棕色小熊挂件和一枚门禁卡。',
    categoryId: 3,
    categoryName: '钥匙',
    location: '第二食堂二楼',
    occurredAt: '2026-10-17 12:15:00',
    contact: '13800000023',
    publisherName: '赵同学',
    allowedStatuses: allowedStatuses('CLAIMED'),
    createdAt: '2026-10-17 12:48:00',
    updatedAt: '2026-10-17 14:10:00',
    deleted: false
  },
  {
    id: 24,
    type: 'LOST',
    status: 'CLOSED',
    title: '弄丢了一把深蓝色折叠伞',
    description: '体育馆南门下雨后忘拿，深蓝色折叠伞，伞柄有一道划痕。',
    categoryId: null,
    categoryName: null,
    location: '体育馆南门',
    occurredAt: '2026-10-16 18:05:00',
    contact: '13800000024',
    publisherName: '孙同学',
    allowedStatuses: allowedStatuses('CLOSED'),
    createdAt: '2026-10-16 20:11:00',
    updatedAt: '2026-10-16 20:11:00',
    deleted: false
  },
  {
    // 已逻辑删除：列表/详情/匹配候选池均不可见，用于验证 20001 分支
    id: 25,
    type: 'LOST',
    status: 'PENDING',
    title: '已删除的测试启事',
    description: '这条记录已被逻辑删除，不应出现在任何读取结果中。',
    categoryId: null,
    categoryName: null,
    location: '未知地点',
    occurredAt: '2026-10-15 10:00:00',
    contact: null,
    publisherName: null,
    allowedStatuses: [],
    createdAt: '2026-10-15 10:05:00',
    updatedAt: '2026-10-15 10:05:00',
    deleted: true
  }
]

interface PersistedDb {
  records: MockRecord[]
  nextId: number
}

function loadPersistedDb(): PersistedDb | null {
  if (typeof sessionStorage === 'undefined') return null
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<PersistedDb>
    if (!Array.isArray(parsed.records) || parsed.records.length === 0) return null
    if (typeof parsed.nextId !== 'number' || !Number.isInteger(parsed.nextId)) return null
    return { records: parsed.records, nextId: parsed.nextId }
  } catch {
    return null
  }
}

const persisted = loadPersistedDb()
const records: MockRecord[] = persisted?.records ?? SEED_RECORDS
let nextId = persisted?.nextId ?? SEED_NEXT_ID

function persist(): void {
  if (typeof sessionStorage === 'undefined') return
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ records, nextId }))
  } catch {
    // 隐私模式/配额不足时退回纯内存，页面功能不受影响
  }
}

function toListItem(record: MockRecord): RecordListItem {
  const { id, type, status, title, categoryId, categoryName, location, occurredAt, createdAt } = record
  return { id, type, status, title, categoryId, categoryName, location, occurredAt, createdAt }
}

function toDetail(record: MockRecord): RecordDetail {
  const { deleted: _deleted, ...rest } = record
  return rest
}

/** GET /api/records（契约 §3）：status 默认 PENDING，ALL 表示不限 */
export function listMockRecords(query: RecordQuery = {}): PageResult<RecordListItem> {
  const status = query.status ?? 'PENDING'
  let result = records.filter((item) => !item.deleted)
  if (query.type) result = result.filter((item) => item.type === query.type)
  if (status !== 'ALL') result = result.filter((item) => item.status === status)
  if (query.categoryId) result = result.filter((item) => item.categoryId === query.categoryId)
  if (query.keyword) {
    const keyword = query.keyword.trim().toLowerCase()
    result = result.filter(
      (item) =>
        item.title.toLowerCase().includes(keyword) || item.description.toLowerCase().includes(keyword)
    )
  }

  const sortKey = query.sortBy === 'occurredAt' ? 'occurredAt' : 'createdAt'
  const direction = query.sortOrder === 'asc' ? 1 : -1
  // 排序稳定：同值按 id DESC（契约 §1）
  result = [...result].sort((a, b) => {
    const diff = a[sortKey].localeCompare(b[sortKey])
    return diff !== 0 ? diff * direction : (b.id - a.id) * direction
  })

  const page = query.page ?? 1
  const pageSize = query.pageSize ?? 10
  const start = (page - 1) * pageSize
  return {
    page,
    pageSize,
    total: result.length,
    totalPages: result.length === 0 ? 0 : Math.ceil(result.length / pageSize),
    hasNext: page * pageSize < result.length,
    list: result.slice(start, start + pageSize).map(toListItem)
  }
}

/** GET /api/records/{id}（契约 §4）：不存在与已删除都返回 null，由 server.ts 转 20001 */
export function getMockRecord(id: number): RecordDetail | null {
  const hit = records.find((item) => item.id === id && !item.deleted)
  return hit ? toDetail(hit) : null
}

const TITLE_RANGE: [number, number] = [2, 64]
const DESC_RANGE: [number, number] = [2, 2000]
const LOCATION_RANGE: [number, number] = [1, 128]

function lengthErrors(field: string, value: string, [min, max]: [number, number]): FieldErrorItem[] {
  const text = value.trim()
  const length = [...text].length
  if (length === 0) return [{ field, reason: '不得为空' }]
  if (length < min || length > max) return [{ field, reason: `长度需在 ${min} 到 ${max} 之间` }]
  return []
}

/** POST /api/records（契约 §5）：返回 fieldErrors 交由 http.ts 抛 BizError(10001) */
export function createMockRecord(
  payload: RecordCreatePayload
): { record: RecordDetail } | { fieldErrors: FieldErrorItem[] } {
  const fieldErrors: FieldErrorItem[] = []
  if (payload.type !== 'LOST' && payload.type !== 'FOUND') {
    fieldErrors.push({ field: 'type', reason: '只能为 LOST 或 FOUND' })
  }
  fieldErrors.push(...lengthErrors('title', payload.title ?? '', TITLE_RANGE))
  fieldErrors.push(...lengthErrors('description', payload.description ?? '', DESC_RANGE))
  fieldErrors.push(...lengthErrors('location', payload.location ?? '', LOCATION_RANGE))
  if (!payload.occurredAt) fieldErrors.push({ field: 'occurredAt', reason: '不得为空' })
  if (payload.contact && payload.contact.length > 64) {
    fieldErrors.push({ field: 'contact', reason: '长度需在 1 到 64 之间' })
  }
  if (payload.publisherName && payload.publisherName.length > 32) {
    fieldErrors.push({ field: 'publisherName', reason: '长度需在 1 到 32 之间' })
  }
  if (fieldErrors.length > 0) return { fieldErrors }

  const category = payload.categoryId
    ? MOCK_CATEGORIES.find((item) => item.id === payload.categoryId)
    : null
  if (payload.categoryId && !category) {
    return { fieldErrors: [{ field: 'categoryId', reason: '分类不存在' }] }
  }
  if (category && !category.active) {
    return { fieldErrors: [{ field: 'categoryId', reason: '分类已停用' }] }
  }

  const createdAt = formatDateTime(new Date())
  const record: MockRecord = {
    id: nextId++,
    type: payload.type,
    status: 'PENDING',
    title: payload.title.trim(),
    description: payload.description.trim(),
    categoryId: category?.id ?? null,
    categoryName: category?.name ?? null,
    location: payload.location.trim(),
    occurredAt: payload.occurredAt,
    contact: payload.contact?.trim() || null,
    publisherName: payload.publisherName?.trim() || null,
    allowedStatuses: allowedStatuses('PENDING'),
    createdAt,
    updatedAt: createdAt,
    deleted: false
  }
  records.unshift(record)
  persist()
  return { record: toDetail(record) }
}

/** PATCH /api/records/{id}/status（契约 §6.1） */
export function patchMockStatus(
  id: number,
  status: RecordStatus
): { result: { id: number; status: RecordStatus; allowedStatuses: RecordStatus[] } } | { code: 20001 | 20002 } {
  const hit = records.find((item) => item.id === id && !item.deleted)
  if (!hit) return { code: 20001 }
  if (!TRANSITIONS[hit.status].includes(status)) return { code: 20002 }
  hit.status = status
  hit.allowedStatuses = allowedStatuses(status)
  hit.updatedAt = formatDateTime(new Date())
  persist()
  return { result: { id: hit.id, status: hit.status, allowedStatuses: [...hit.allowedStatuses] } }
}

/** GET /api/categories（契约 §7）：默认只返 active，已按 sortOrder 升序 */
export function listMockCategories(onlyActive: boolean): CategoryItem[] {
  return MOCK_CATEGORIES.filter((item) => !onlyActive || item.active).sort(
    (a, b) => a.sortOrder - b.sortOrder
  )
}

/** 本地关键词打分：分数归一到 [0,1]，只承诺同一实现内相对排序可比 */
let nextMatchId = 41

/**
 * 中文按 2-gram、英文/数字按词切分。真实语义引擎由 3.2 接入，
 * 这里只保证同一实现内「越相关分越高」，且能做到排序稳定（契约 §8.4）。
 */
function tokenize(text: string): string[] {
  const normalized = text.toLowerCase()
  const tokens = new Set<string>()
  for (const word of normalized.match(/[a-z0-9]+/g) ?? []) tokens.add(word)
  for (const run of normalized.match(/[\u4e00-\u9fa5]+/g) ?? []) {
    if (run.length === 1) tokens.add(run)
    for (let i = 0; i < run.length - 1; i++) tokens.add(run.slice(i, i + 2))
  }
  return [...tokens]
}

export function mockMatch(payload: MatchPayload): { result: MatchResult } | { fieldErrors: FieldErrorItem[] } {
  const text = payload.text?.trim() ?? ''
  if ([...text].length < 2 || [...text].length > 2000) {
    return { fieldErrors: [{ field: 'text', reason: '长度需在 2 到 2000 之间' }] }
  }

  const candidatePool = records.filter(
    (item) => !item.deleted && item.status === 'PENDING' && item.id !== payload.excludeRecordId
  )
  const candidates = payload.type ? candidatePool.filter((item) => item.type === payload.type) : candidatePool

  const queryTokens = tokenize(text)
  const items = candidates
    .map((record) => {
      const target = `${record.title} ${record.description} ${record.location} ${record.categoryName ?? ''}`
      const targetTokens = new Set(tokenize(target))
      const hits = queryTokens.filter((token) => targetTokens.has(token))
      // 文本命中为主，相同分类只做加权（契约 §8.1：categoryId 加权/过滤皆可）
      const textScore = Math.min(1, hits.length / 10)
      const categoryHit =
        payload.categoryId !== undefined &&
        payload.categoryId !== null &&
        record.categoryId === payload.categoryId
      const score = Math.round(Math.min(1, textScore * 0.7 + (categoryHit ? 0.3 : 0)) * 100) / 100
      // 标题里的命中词更能说明「为什么是这条」，优先展示
      const titleTokens = new Set(tokenize(record.title))
      const shownHits = (hits.filter((token) => titleTokens.has(token)).length > 0
        ? hits.filter((token) => titleTokens.has(token))
        : hits
      ).slice(0, 3)
      return {
        recordId: record.id,
        score,
        scorePercent: Math.round(score * 100),
        reason:
          hits.length > 0
            ? `命中关键词「${shownHits.join('」「')}」等 ${hits.length} 个词`
            : categoryHit
              ? '分类相同，文本未命中关键词'
              : '未命中关键词，相似度为 0',
        type: record.type,
        status: record.status,
        title: record.title,
        categoryName: record.categoryName,
        location: record.location,
        occurredAt: record.occurredAt,
        createdAt: record.createdAt
      }
    })
    .filter((item) => item.score > 0)
    .sort((a, b) => (b.score !== a.score ? b.score - a.score : a.recordId - b.recordId))

  const topN = payload.topN ?? 5
  const minScore = payload.minScore ?? 0.3
  const picked = items.filter((item) => item.score >= minScore).slice(0, topN)

  return {
    result: {
      matchId: nextMatchId++,
      engine: 'LOCAL_KEYWORD',
      inputType: payload.type ?? null,
      topN,
      minScore,
      tookMs: 18,
      total: picked.length,
      items: picked
    }
  }
}
