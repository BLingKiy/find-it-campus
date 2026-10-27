import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import { useAuth } from './composables/useAuth'

// 顺序有意义：tokens（变量）→ base（reset/排版/无障碍地基）→ components（组件原始类）→ utilities
import './styles/tokens.css'
import './styles/base.css'
import './styles/components.css'
import './styles/utilities.css'

const { clearSession } = useAuth()

// 401 统一落点（B3 §2）：清本地登录态 → 带 redirect 跳登录页。
// 并发 401 的合并已在 http.ts 完成，这里只处理"当前页"这一次跳转。
setUnauthorizedHandler(({ path, method }) => {
  clearSession()
  const current = router.currentRoute.value
  if (current.name === 'Login') return
  console.info(`[auth] ${method} ${path} 返回 401，跳转登录`)
  void router.replace({ name: 'Login', query: { redirect: current.fullPath } })
})

createApp(App).use(router).mount('#app')
