// 请求层单点（基线 frontend-baseline.md §5.2）：页面里不出现 fetch
import type { ApiResponse, FieldErrorItem } from '@/types/api'

const DEFAULT_TIMEOUT_MS = 8000
/** 网络失败/超时的业务码（契约 §9：10005 与网络失败同处置） */
export const ERR_NETWORK = -1
const NETWORK_ERROR_MESSAGE = '网络请求失败，请稍后再试'
const UNKNOWN_ERROR_MESSAGE = '请求失败，请稍后再试'

const baseURL: string = import.meta.env.VITE_API_BASE_URL ?? '/api'

// TODO(4.1): 后端 ZHYYKFX-6（3.1）就绪后删除本开关与 src/mock 目录；届时 request 只剩 fetch 一条路径。
// 字符串比较：未设置或 =false 一律走真实接口。mock 只替换「取响应体」这一步，解包与报错逻辑共用。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

export class BizError extends Error {
  code: number
  requestId: string | null
  fieldErrors: FieldErrorItem[]
  status: number

  constructor(init: {
    message: string
    code: number
    status: number
    requestId?: string | null
    fieldErrors?: FieldErrorItem[]
  }) {
    super(init.message)
    this.name = 'BizError'
    this.code = init.code
    this.status = init.status
    this.requestId = init.requestId ?? null
    this.fieldErrors = init.fieldErrors ?? []
    Object.setPrototypeOf(this, BizError.prototype)
  }
}

export type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** null / undefined 自动丢弃 */
  query?: Record<string, QueryValue>
  body?: unknown
  /** 默认 8000ms；POST /api/match 传 12000 */
  timeoutMs?: number
}

function buildSearch(query: RequestOptions['query']): string {
  if (!query) return ''
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue
    search.append(key, String(value))
  }
  const qs = search.toString()
  return qs ? `?${qs}` : ''
}

export async function request<T>(path: string, init: RequestOptions = {}): Promise<T> {
  const { method = 'GET', query, body, timeoutMs = DEFAULT_TIMEOUT_MS } = init
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const url = `${baseURL}${path}${buildSearch(query)}`

  try {
    let response: { ok: boolean; status: number }
    let payload: Partial<ApiResponse<T>> | null

    if (USE_MOCK) {
      const { mockApi } = await import('@/mock/server')
      const mockQuery: Record<string, string> = {}
      for (const [key, value] of Object.entries(query ?? {})) {
        if (value === null || value === undefined || value === '') continue
        mockQuery[key] = String(value)
      }
      const mocked = await mockApi({ path, method, query: mockQuery, body })
      response = { ok: mocked.status >= 200 && mocked.status < 300, status: mocked.status }
      payload = mocked.body as Partial<ApiResponse<T>>
    } else {
      const raw = await fetch(url, {
        method,
        headers: {
          Accept: 'application/json',
          // 写接口必须显式声明，否则 Spring Boot 的 @RequestBody 无法解析（415）
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal
      })
      response = { ok: raw.ok, status: raw.status }
      payload = null
      try {
        payload = (await raw.json()) as Partial<ApiResponse<T>>
      } catch {
        payload = null
      }
    }

    const code = typeof payload?.code === 'number' ? payload.code : -1
    const requestId = payload?.requestId ?? null
    const message = payload?.message || (response.ok ? UNKNOWN_ERROR_MESSAGE : `HTTP ${response.status}`)
    const fieldErrors =
      (payload?.data as { fieldErrors?: FieldErrorItem[] } | null)?.fieldErrors ?? []

    // 唯一成功判据：2xx 且 code === 0（契约 §1）
    if (response.ok && code === 0) {
      return payload?.data as T
    }
    throw new BizError({ message, code, status: response.status, requestId, fieldErrors })
  } catch (err) {
    if (err instanceof BizError) throw err
    throw new BizError({ message: NETWORK_ERROR_MESSAGE, code: ERR_NETWORK, status: 0 })
  } finally {
    clearTimeout(timer)
  }
}

/** 错误文案唯一出口（契约 §9 映射表）；组件里不 switch 数字 */
export function describeError(err: unknown): string {
  if (err instanceof BizError) {
    if (err.code === ERR_NETWORK) return NETWORK_ERROR_MESSAGE
    if (err.code === 30002 || err.code === 30003) return '匹配服务暂不可用，请稍后再试'
    if (err.code === 10005) return `${UNKNOWN_ERROR_MESSAGE}${err.requestId ? `（${err.requestId}）` : ''}`
    if (err.code === 10003 || err.code === 10004) {
      // 视为调用不存在的接口，属开发期缺陷
      console.error('[api] 调用了不存在的接口：', err.code, err.requestId)
      return `${err.message || UNKNOWN_ERROR_MESSAGE}${err.requestId ? `（${err.requestId}）` : ''}`
    }
    return err.message || UNKNOWN_ERROR_MESSAGE
  }
  if (err instanceof Error) return err.message
  return UNKNOWN_ERROR_MESSAGE
}
