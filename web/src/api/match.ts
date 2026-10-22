import type { MatchPayload, MatchResult } from '@/types/api'
import { request } from './http'

/** POST /api/match（契约 §8）；引擎比关键词排序慢，超时放宽到 12000ms */
export function match(payload: MatchPayload): Promise<MatchResult> {
  return request<MatchResult>('/match', {
    method: 'POST',
    body: payload,
    timeoutMs: 12000
  })
}
