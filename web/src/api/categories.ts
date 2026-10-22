import type { CategoryItem } from '@/types/api'
import { request } from './http'

/** GET /api/categories（契约 §7） */
export function listCategories(onlyActive = true): Promise<CategoryItem[]> {
  return request<CategoryItem[]>('/categories', { query: { onlyActive } })
}
