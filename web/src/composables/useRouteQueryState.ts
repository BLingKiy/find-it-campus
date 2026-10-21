// query ↔ 本地 ref 双向同步（基线 §3.1：筛选与分页写进 URL，URL 即状态）
import { ref, watch } from 'vue'
import type { Ref } from 'vue'
import type { LocationQuery } from 'vue-router'
import { useRoute, useRouter } from 'vue-router'

export type QueryState = Record<string, string | number>

/** URL query 与本地 query state 都能喂进来做「按 defaults 取值 + 归一」 */
function pick(source: LocationQuery | QueryState, defaults: QueryState): QueryState {
  const result: QueryState = { ...defaults }
  for (const key of Object.keys(defaults)) {
    const raw = (source as Record<string, unknown>)[key]
    if (raw === undefined || raw === null || raw === '') continue
    const value = Array.isArray(raw) ? raw[0] : raw
    if (typeof value !== 'string' && typeof value !== 'number') continue
    result[key] = typeof defaults[key] === 'number' ? Number(value) || defaults[key] : value
  }
  return result
}

/** 骨架阶段只做初始值读取 + 写回 URL；筛选表单的具体字段由 4.1 填充 */
export function useRouteQueryState(defaults: QueryState): Ref<QueryState> {
  const route = useRoute()
  const router = useRouter()
  const state = ref<QueryState>(pick(route.query, defaults)) as Ref<QueryState>

  watch(
    () => route.query,
    (query) => {
      state.value = pick(query, defaults)
    }
  )

  watch(
    state,
    (value) => {
      if (JSON.stringify(value) === JSON.stringify(pick(route.query, defaults))) return
      const next = pick(value, defaults)
      const query: Record<string, string> = {}
      for (const [key, item] of Object.entries(next)) query[key] = String(item)
      void router.push({ path: route.path, query })
    },
    { deep: true }
  )

  return state
}
