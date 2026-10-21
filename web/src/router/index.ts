import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const DEFAULT_PAGE = 1
const DEFAULT_PAGE_SIZE = 10
const MAX_PAGE_SIZE = 50

function normalizePositiveInt(value: unknown, fallback: number, max = Number.MAX_SAFE_INTEGER): number {
  const num = Number(value)
  if (!Number.isInteger(num) || num < 1 || num > max) return fallback
  return num
}

// 顺序有意为之：/records/new 必须声明在 /records/:id 之前（基线 §2）
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'RecordList',
    component: () => import('@/views/RecordList.vue')
  },
  {
    path: '/records/new',
    name: 'RecordCreate',
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
  routes
})

// 全局前置守卫：只归一 page/pageSize，不在守卫里发请求（基线 §2）
router.beforeEach((to) => {
  const rawPage = to.query.page
  const rawPageSize = to.query.pageSize
  if (rawPage === undefined && rawPageSize === undefined) return true

  const page = normalizePositiveInt(rawPage, DEFAULT_PAGE)
  const pageSize = normalizePositiveInt(rawPageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE)
  if (String(page) === String(rawPage) && String(pageSize) === String(rawPageSize)) return true

  return {
    path: to.path,
    hash: to.hash,
    query: { ...to.query, page: String(page), pageSize: String(pageSize) },
    replace: true
  }
})

export default router
