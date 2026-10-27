import { createRouter, createWebHistory } from 'vue-router'
import type {
  LocationQueryRaw,
  RouteLocationNormalized,
  RouteLocationRaw,
  RouteRecordRaw
} from 'vue-router'
import { useAuth } from '@/composables/useAuth'

const DEFAULT_PAGE = 1
const DEFAULT_PAGE_SIZE = 10
const MAX_PAGE_SIZE = 50

/**
 * 需要登录的页面（B3 §4）：
 * - meta.requiresAuth：已存在的路由（发布页）显式标注；
 * - 下面的路径规则给"编辑 / 我的启事 / 通知"留位 —— 这些页面在 C2/E2 才落地，
 *   先按路由名路径兜住，避免新页面忘了加 meta 就变成匿名可进。
 */
const AUTH_REQUIRED_PATHS = new Set(['/records/new', '/my', '/notifications'])

function normalizePositiveInt(value: unknown, fallback: number, max = Number.MAX_SAFE_INTEGER): number {
  const num = Number(value)
  if (!Number.isInteger(num) || num < 1 || num > max) return fallback
  return num
}

function normalizePageQuery(to: RouteLocationNormalized): RouteLocationRaw | null {
  const rawPage = to.query.page
  const rawPageSize = to.query.pageSize
  if (rawPage === undefined && rawPageSize === undefined) return null

  const page = normalizePositiveInt(rawPage, DEFAULT_PAGE)
  const pageSize = normalizePositiveInt(rawPageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE)
  if (String(page) === String(rawPage) && String(pageSize) === String(rawPageSize)) return null

  return {
    path: to.path,
    hash: to.hash,
    query: { ...to.query, page: String(page), pageSize: String(pageSize) },
    replace: true
  }
}

function requiresLogin(to: RouteLocationNormalized): boolean {
  if (to.meta.requiresAuth === true) return true
  const path = to.path.length > 1 ? to.path.replace(/\/+$/, '') : to.path
  if (AUTH_REQUIRED_PATHS.has(path)) return true
  if (path.startsWith('/my/') || path.startsWith('/notifications/')) return true
  return /^\/records\/[^/]+\/edit$/.test(path)
}

/**
 * 统一 query 序列化：数组保持重复键，null/undefined 丢弃。
 * 用 encodeURIComponent 而不是 URLSearchParams：后者把空格编码成 "+"，
 * 而 vue-router 的解析端只做 decodeURIComponent，会把 "+" 原样留在值里。
 */
function stringifyQuery(query: LocationQueryRaw | undefined): string {
  if (!query) return ''
  const parts: string[] = []
  const append = (key: string, value: unknown) => {
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined) continue
    if (Array.isArray(value)) {
      for (const item of value) {
        if (item !== null && item !== undefined) append(key, item)
      }
    } else {
      append(key, value)
    }
  }
  return parts.join('&')
}

/**
 * 登录后回跳目标只接受站内绝对路径：
 * `//evil.example`（协议相对）与 /login 自身都要挡掉，否则会成为开放重定向或登录死循环。
 */
export function resolveLoginRedirect(raw: unknown): string {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string' || value === '') return '/'
  if (!value.startsWith('/') || value.startsWith('//')) return '/'
  const path = value.split(/[?#]/)[0]
  if (path === '/login') return '/'
  return value
}

// 顺序有意为之：/records/new 必须声明在 /records/:id 之前（基线 §2）
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'RecordList',
    component: () => import('@/views/RecordList.vue')
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue')
  },
  {
    path: '/records/new',
    name: 'RecordCreate',
    meta: { requiresAuth: true },
    component: () => import('@/views/RecordCreate.vue')
  },
  {
    path: '/records/:id',
    name: 'RecordDetail',
    component: () => import('@/views/RecordDetail.vue')
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue')
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  // vue-router 默认把 query 里的 "/" 保留为原字符；登录回跳参数按 "路径" 语义处理，
  // 统一用 URLSearchParams 编码（%2F），地址栏与验收口径一致，分享/刷新也不变。
  stringifyQuery,
  routes
})

// 全局前置守卫：先归一 page/pageSize，再判登录（守卫里只发 /auth/me 这一次探测，不拉业务数据）
router.beforeEach(async (to) => {
  const normalized = normalizePageQuery(to)
  if (normalized) return normalized

  if (!requiresLogin(to)) return true

  const { ensureSession } = useAuth()
  const user = await ensureSession()
  if (user) return true

  return { name: 'Login', query: { redirect: to.fullPath } }
})

export default router
