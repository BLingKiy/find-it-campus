import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 照抄 docs/frontend-baseline.md §8：端口 5173、'@' → src、/api 代理到后端
// 目标端口 18080（对齐 server/application.yml 的 server.port）—— 不是 8080，
// 因为开发机上 8080 已被另一个服务占用。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:18080', changeOrigin: true }
    }
  },
  // 预览（dist 产物）也走同一条 /api 代理：C2 的 DOM 扫描必须在生产构建上做，
  // dev server 会给脚本 URL 带 `?t=<13 位毫秒>` 热更新戳，那也是 11 位数字串（误报）。
  preview: {
    port: 4173,
    proxy: {
      '/api': { target: 'http://localhost:18080', changeOrigin: true }
    }
  },
  build: { outDir: 'dist', sourcemap: false }
})
