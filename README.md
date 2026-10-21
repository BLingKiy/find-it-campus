# find-it-campus · 校园失物招领

一条命令行跑起来的失物招领：寻物/招领启事发布、检索与语义匹配。

## 仓库结构

```
README.md
docs/        1.2 交付的 5 份基线文档（事实源）
web/         Vue 3 + TypeScript + Vite 前端
db/         由 2.1-B 后端骨架补充（建表脚本）
server/     由 2.1-B 后端骨架补充（Spring Boot）
```

技术依据与约定见 `docs/`：architecture-baseline.md（分层 / 错误码 / 环境）、api-contract.md（接口事实源）、ai-match-module.md（匹配模块边界）、frontend-baseline.md（前端结构基线）、open-questions.md（待拍板项）。

## 环境要求（前端）

| 组件 | 版本 | 说明 |
|---|---|---|
| Node.js | 22.x LTS（实测 v22.23.3 / npm 10.9.9） | Vite 8 要求 `^20.19.0 \|\| >=22.12.0` |

仓库依赖的实际锁定版本以 `web/package-lock.json` 为准。

## 前端（web 目录）

```bash
cd web
npm install
npm run dev          # http://localhost:5173 ，/api 经 Vite 代理转发到 http://localhost:8080
npm run build        # 产物 dist/
npm run preview      # 本地预览构建产物
npm run type-check   # vue-tsc --noEmit（src 与 vite.config.ts 两组配置）
```

环境变量：复制 `web/.env.example` 为 `web/.env.local` 后按需修改（`.env.local` 不提交）。默认 `VITE_API_BASE_URL=/api`，走 Vite 代理、浏览器视角同源无需 CORS；若改成 `http://localhost:8080/api` 则前端直连后端，依赖后端 CORS 配置（见 `docs/architecture-baseline.md` §9.4）。

> 演示用 dev server。生产打包部署不在本项目范围内。

## 后端 / 数据库

由 2.1-B（后端骨架）补充：环境要求、`server/` 启动与构建命令、`db/` 三条命令。
