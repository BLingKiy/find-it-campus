// TODO(4.1): 临时 mock 网关（后端 3.1 尚未可调用）。ZHYYKFX-6 就绪后连同 src/mock 一起删除。
// 它按 docs/api-contract.md 的路由/状态码/错误码返回，http.ts 拿到后走与真实请求完全相同的解包逻辑，
// 因此两种模式下页面看到的行为一致：mock 不是另一套 RPC，只是换掉了 fetch。
import type {
  ApiResponse,
  MatchPayload,
  RecordCreatePayload,
  RecordQuery,
  RecordStatus
} from '@/types/api'
import {
  createMockRecord,
  getMockRecord,
  listMockCategories,
  listMockRecords,
  mockMatch,
  patchMockStatus
} from './store'

export interface MockRequest {
  path: string
  method: string
  query: Record<string, string>
  body: unknown
}

export interface MockResponse {
  status: number
  body: Partial<ApiResponse<unknown>>
}

const REQUEST_ID = 'mock00000001'

function ok<T>(data: T, message = 'ok'): MockResponse {
  return { status: 200, body: { code: 0, message, data, requestId: REQUEST_ID } }
}

function fail(status: number, code: number, message: string, data: unknown = null): MockResponse {
  return { status, body: { code, message, data, requestId: REQUEST_ID } }
}

function toNumber(value: string | undefined): number | undefined {
  if (value === undefined || value === '') return undefined
  const num = Number(value)
  return Number.isFinite(num) ? num : undefined
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 路由表与 api/*.ts 的请求一一对应（/records、/records/{id}、/categories、/match） */
export async function mockApi(req: MockRequest): Promise<MockResponse> {
  // 轻微延迟：让 loading 态在演示里可见，也让竞态处理真的被走到
  await sleep(req.path.startsWith('/match') ? 500 : 200)

  const id = toNumber(req.path.split('/')[2])

  if (req.method === 'GET' && req.path === '/records') {
    const query: RecordQuery = {
      type: req.query.type as RecordQuery['type'],
      status: (req.query.status as RecordQuery['status']) ?? 'PENDING',
      categoryId: toNumber(req.query.categoryId),
      keyword: req.query.keyword || undefined,
      page: toNumber(req.query.page) ?? 1,
      pageSize: toNumber(req.query.pageSize) ?? 10,
      sortBy: req.query.sortBy as RecordQuery['sortBy'],
      sortOrder: req.query.sortOrder as RecordQuery['sortOrder']
    }
    return ok(listMockRecords(query))
  }

  if (req.method === 'GET' && id !== undefined && req.path === `/records/${id}`) {
    if (!Number.isInteger(id) || id < 1) {
      return fail(400, 10001, '请求参数校验失败', {
        fieldErrors: [{ field: 'id', reason: '必须为数字' }]
      })
    }
    const record = getMockRecord(id)
    if (!record) return fail(404, 20001, '记录不存在或已删除')
    return ok(record)
  }

  if (req.method === 'POST' && req.path === '/records') {
    const outcome = createMockRecord(req.body as RecordCreatePayload)
    if ('fieldErrors' in outcome) {
      return fail(400, 10001, '请求参数校验失败', { fieldErrors: outcome.fieldErrors })
    }
    return ok(outcome.record)
  }

  if (req.method === 'PATCH' && id !== undefined && req.path === `/records/${id}/status`) {
    const target = (req.body as { status?: RecordStatus })?.status
    if (!target) return fail(400, 10001, '请求参数校验失败')
    const outcome = patchMockStatus(id, target)
    if ('code' in outcome) {
      return outcome.code === 20001
        ? fail(404, 20001, '记录不存在或已删除')
        : fail(409, 20002, '当前状态不允许该流转', { current: 'CLOSED', allowed: ['(无)'] })
    }
    return ok(outcome.result, '状态已更新')
  }

  if (req.method === 'GET' && req.path === '/categories') {
    return ok(listMockCategories(req.query.onlyActive !== 'false'))
  }

  if (req.method === 'POST' && req.path === '/match') {
    const outcome = mockMatch(req.body as MatchPayload)
    if ('fieldErrors' in outcome) {
      return fail(400, 30001, '匹配文本不合法', { fieldErrors: outcome.fieldErrors })
    }
    return ok(outcome.result)
  }

  // 与契约 §9 一致：调用了不存在的接口属开发期缺陷
  return fail(404, 10003, `接口不存在：${req.method} ${req.path}`)
}
