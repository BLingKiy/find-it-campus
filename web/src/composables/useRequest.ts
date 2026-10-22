// 数据/错误/加载的唯一请求时序入口（基线 §5.3）
import { shallowRef } from 'vue'
import type { Ref } from 'vue'

export interface UseRequestOptions {
  immediate?: boolean
}

export function useRequest<T, A extends unknown[]>(
  fn: (...args: A) => Promise<T>,
  options: UseRequestOptions = {}
) {
  const data: Ref<T | null> = shallowRef(null)
  const error: Ref<unknown> = shallowRef(null)
  const loading: Ref<boolean> = shallowRef(false)
  let sequence = 0

  async function run(...args: A): Promise<T | null> {
    const ticket = ++sequence
    loading.value = true
    error.value = null
    try {
      const result = await fn(...args)
      // 竞态：丢弃过期响应（列表页快速切筛选时不渲染旧结果）
      if (ticket !== sequence) return null
      data.value = result
      return result
    } catch (err) {
      if (ticket !== sequence) return null
      error.value = err
      return null
    } finally {
      if (ticket === sequence) loading.value = false
    }
  }

  if (options.immediate) {
    void run(...([] as unknown as A))
  }

  return { data, error, loading, run }
}
