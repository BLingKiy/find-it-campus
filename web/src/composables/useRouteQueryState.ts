// query ↔ 本地 ref 双向同步（基线 §3.1：筛选与分页写进 URL，URL 即状态）
import { ref, watch } from 'vue'
import type { Ref } from 'vue'
import type { LocationQuery } from 'vue-router'
import { useRoute, useRouter } from 'vue-router'

export type QueryState = Record<string, string | number>

/** URL query 与本地 query state 都能喂进来做「按 defaults 取值 + 归一」 */
function pick<T extends QueryState>(source: LocationQuery | QueryState, defaults: T): T {
  const result: QueryState = { ...defaults }
  for (const key of Object.keys(defaults)) {
    const raw = (source as Record<string, unknown>)[key]
    if (raw === undefined || raw === null || raw === '') continue
    const value = Array.isArray(raw) ? raw[0] : raw
    if (typeof value !== 'string' && typeof value !== 'number') continue
    result[key] = typeof defaults[key] === 'number' ? Number(value) || defaults[key] : value
  }
  return result as T
}

/**
 * 写回 URL 时丢弃「等于默认值」与「空值」的项。否则分享链接会长成
 * /?type=ALL&status=PENDING&keyword=&page=1&pageSize=10…，可读性为零。
 */
function prune<T extends QueryState>(value: T, defaults: T): Record<string, string> {
  const query: Record<string, string> = {}
  for (const [key, raw] of Object.entries(value)) {
    if (raw === '' || raw === null || raw === undefined) continue
    if (String(raw) === String(defaults[key])) continue
    query[key] = String(raw)
  }
  return query
}

export function useRouteQueryState<T extends QueryState>(defaults: T): Ref<T> {
  const route = useRoute()
  const router = useRouter()
  const state = ref<T>(pick(route.query, defaults)) as Ref<T>

  watch(
    () => route.query,
    (query) => {
      state.value = pick(query, defaults)
    }
  )

  watch(
    state,
    (value) => {
      const next = pick(value, defaults)
      if (JSON.stringify(next) === JSON.stringify(pick(route.query, defaults))) return
      // 只保留不属于本次 state 的其他 query，防止外部参数被吃掉；属于本次 state 的键
      // 一律以 next 为准。若沿用 route.query 的旧值，重置筛选后 keyword/type 会留在
      // URL 里，刷新时筛选复活（DEF-02）。
      const query: LocationQuery = { ...route.query }
      for (const key of Object.keys(defaults)) delete query[key]
      void router.push({ path: route.path, query: { ...query, ...prune(next, defaults) } })
    },
    { deep: true }
  )

  return state
}
