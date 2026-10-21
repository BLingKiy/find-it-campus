// 分类字典模块级缓存（基线 §3.1）：只读、几乎不变，并发去重 + 手动失效，不引入 Pinia
import type { CategoryItem } from '@/types/api'
import { listCategories } from './categories'

let cache: CategoryItem[] | null = null
let inflight: Promise<CategoryItem[]> | null = null

export function loadCategories(): Promise<CategoryItem[]> {
  if (cache) return Promise.resolve(cache)
  if (inflight) return inflight
  inflight = listCategories()
    .then((list) => {
      cache = list
      return list
    })
    .finally(() => {
      inflight = null
    })
  return inflight
}

export function invalidateCategoryCache(): void {
  cache = null
}
