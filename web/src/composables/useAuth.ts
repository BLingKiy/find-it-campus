// 登录态单例（与 categoryCache 同思路：不引 Pinia，模块级状态 + 并发去重）。
// 唯一事实源是服务端 session cookie：这里只缓存 /api/auth/me 的结果，不写 localStorage、不存 token。
import { computed, readonly, ref } from 'vue'
import { fetchMe, login as loginApi, logout as logoutApi } from '@/api/auth'
import { BizError } from '@/api/http'
import type { LoginPayload, UserInfo } from '@/types/api'

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous'

const currentUser = ref<UserInfo | null>(null)
const status = ref<AuthStatus>('unknown')
/** 并发探测合并：路由守卫与 App 启动可能同时调 ensureSession，只发一次 /auth/me */
let bootstrap: Promise<UserInfo | null> | null = null

const isAuthenticated = computed(() => status.value === 'authenticated' && currentUser.value !== null)

/** 首次进入/刷新时恢复登录态；未登录（401）是预期分支，不报错 */
async function ensureSession(): Promise<UserInfo | null> {
  if (status.value !== 'unknown') return currentUser.value
  if (bootstrap) return bootstrap
  bootstrap = (async () => {
    try {
      const user = await fetchMe()
      currentUser.value = user
      status.value = 'authenticated'
      return user
    } catch (err) {
      if (err instanceof BizError && err.status === 401) {
        currentUser.value = null
        status.value = 'anonymous'
        return null
      }
      // 网络/服务端异常：保持 unknown，下次导航或页面动作会再试，不把浏览页拦在门外
      return null
    } finally {
      bootstrap = null
    }
  })()
  return bootstrap
}

async function signIn(payload: LoginPayload): Promise<UserInfo> {
  const user = await loginApi(payload)
  currentUser.value = user
  status.value = 'authenticated'
  return user
}

/** 服务端已清会话才清本地；登出失败时保持登录态，避免"界面退出但会话还在" */
async function signOut(): Promise<void> {
  await logoutApi()
  clearSession()
}

/** 401 统一处理/登出后调用 */
function clearSession(): void {
  currentUser.value = null
  status.value = 'anonymous'
}

export function useAuth() {
  return {
    user: readonly(currentUser),
    status: readonly(status),
    isAuthenticated,
    ensureSession,
    signIn,
    signOut,
    clearSession
  }
}
