/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端基础路径，默认同源 /api（走 Vite 代理） */
  readonly VITE_API_BASE_URL?: string
  /** 'true' 时 listRecords 走本地 mock；未设置或 'false' 走真实接口 */
  readonly VITE_USE_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
