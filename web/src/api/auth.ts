// 认证接口（契约 §4.9–§4.11）：登录 / 登出 / 当前用户。
// 三个接口都标记 silentAuth：登录失败的 401 是「凭证错」，/auth/me 的 401 是「还没登录」，
// 都不该触发全局登录引导（否则登录页自己会把自己重定向成死循环）。
import type { LoginPayload, UserInfo } from '@/types/api'
import { request } from './http'

/** POST /api/auth/login：成功由服务端下发 HttpOnly 的 JSESSIONID，前端不存 token */
export function login(payload: LoginPayload): Promise<UserInfo> {
  return request<UserInfo>('/auth/login', { method: 'POST', body: payload, silentAuth: true })
}

/** POST /api/auth/logout：幂等，未登录调用也 200 */
export function logout(): Promise<null> {
  return request<null>('/auth/logout', { method: 'POST', silentAuth: true })
}

/** GET /api/auth/me：登录态的唯一事实源（刷新后靠它恢复） */
export function fetchMe(): Promise<UserInfo> {
  return request<UserInfo>('/auth/me', { silentAuth: true })
}
