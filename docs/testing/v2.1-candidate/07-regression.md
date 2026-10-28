# 回归：后端全量测试 + 前端类型检查/构建

工作树：`C:\Project\hifox-workspaces\panshishi\m6-work`（`30002de`，分支 `test/v2-M6-candidate-package`）。原始日志：[`regression/`](regression/)。

## 1. 后端

命令（`server/` 目录内，离线）：

```powershell
cd server
.\mvnw.cmd -o test
```

结果：

```
[INFO] Tests run: 297, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
EXIT=0
```

- 与调度基线 `297 / 0 / 0 / 1` 完全一致。
- Skip 的 1 条是既有「A/B（真实 DeepSeek，无 key 时 skip）」用例（日志原文：`A/B ... （真实 DeepSeek，无 key）`），不是本单新增 skip。
- 完整日志：[`regression/mvn-test.log`](regression/mvn-test.log)。

## 2. 前端类型检查

```powershell
cd web
npm run type-check
```

结果：

```
> vue-tsc --noEmit -p tsconfig.json && vue-tsc --noEmit -p tsconfig.node.json
EXIT=0
```

两个 tsconfig 均通过；日志：[`regression/web-typecheck.log`](regression/web-typecheck.log)。

## 3. 前端构建

```powershell
cd web
$env:NODE_OPTIONS='--require C:\Project\hifox-workspaces\panshishi\vite-exec-shim.cjs'
npm run build
```

结果：`EXIT=0`，Vite 输出 `✓ built in 865ms`，产物在 `web/dist/`（`index.html` + `assets/*`）。日志：[`regression/web-build.log`](regression/web-build.log)。

> 沙箱说明：直接 `npm run build` 在本机受限沙箱会因 Vite 的 `windowsSafeRealPathSync` → `child_process.exec('net use')` 被拒而报 `spawn EPERM`。`vite-exec-shim.cjs` 只把 `net use` 返回为空（工作区在本地盘，与真实结果等价），不改 Vite 配置、不进入产物；这与 Z-84 轮处理同类沙箱限制的口径一致。普通开发机不需要该 shim。

## 4. 代码面

本分支相对 `30002de` 只新增 `docs/testing/v2.1-candidate/` 证据，未修改 `server/`、`web/`、`db/` 业务代码、断言、表结构或默认配置。`git diff --stat` 见提交说明。
