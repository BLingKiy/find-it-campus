# P3 修复① 证据：页脚 dev server 字样与 BUILD-INFO 布局版本

- 分支：`fix/p3-footer-buildinfo`
- 基线：`268edef`（含 Z-42 seed #26 文案修复）
- 代码/文档提交：`4c66a22`
- 部署包：`deploy/dist/find-it-campus-windows-x64-4c66a22.zip`（未入库）
- 部署包 SHA256：`6FC31D68CAF05F3175541C3F968E1E7EF11756840A5E4586580B26E42DE92D69`
- 解压目录（本机验收用）：`C:\Project\hifox-workspaces\wubage\w43\clean\find-it-campus-windows-x64`

## 改动

1. `web/src/App.vue`：删除页脚 `演示环境（dev server）` 整句及其专属样式，页脚只保留 `find-it-campus · 校园失物招领`。
2. `deploy/build-package.ps1`：`Package layout version: 7.1` → `7.4`，其余行与 BOM 未变。
3. `docs/api-contract.md`（2 处）、`docs/design/program-design.md`（1 处）：#26 示例文案改为 `捡到一张浅蓝色卡片`。
4. `docs/design/program-design.docx`：用 `docs/design/tools/build-docx.mjs` 重导。
5. PPT：`docs/delivery` 下生成脚本与 pptx 均不含该文案，未重导（见 `18-ppt-check.txt`）。

## 验证

在干净解压目录按 `deploy\DEPLOY-WINDOWS.md` 执行（本机 MySQL 8.0.29、JDK 17.0.20.1）：

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1 -DbHost 127.0.0.1 -DbPort 3306 -DbName find_it_campus -DbUser root -EmptyDbPassword -Engine local -MysqlBin <mysql.exe> -JavaBin <java.exe>
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop.ps1
```

结果：四条命令退出码 `0`，`status.ps1` 输出 `RESULT: HEALTHY`（8088 / 18080 均 UP）。

- 8088 三页面 `/`、`/records/26`、`/records/new` 均 200；截图见 `07`/`08`/`09`，页脚只有 `find-it-campus · 校园失物招领`，无 `dev server` / `演示环境`。
- 包内 `web/dist` 全量检索 `dev server`、`演示环境` 命中 0。
- `BUILD-INFO.txt`：`Package layout version: 7.4`、`Git branch: fix/p3-footer-buildinfo`、`Git commit: 4c66a22…`、`Git dirty: false`；jar/index SHA256 与包内实际文件一致（`02-build-info-check.txt`）。
- 5.1 接口回归（对 8088 公共入口跑）：64/64 通过，退出码 0。
- `/api/match` 冒烟：HTTP 200，`total=1`，top1 `recordId=13`、`scorePercent=95`；请求/响应原文见 `12`/`13`。
- 文档：源码三处已无 `unknown 的卡片`；docx 内文为 `捡到一张浅蓝色卡片`（1 处），旧 docx 仍为占位符，见 `17-docx-check.txt`。

## 文件索引

| 文件 | 内容 |
|---|---|
| `00-package-sha256.txt` | 包路径、zip SHA256、大小、解压目录 |
| `01-BUILD-INFO.txt` | 新包 BUILD-INFO 原文 |
| `02-build-info-check.txt` | 布局版本 / 提交 / jar 与 index SHA 的一致性核对 |
| `03-install.txt` | install.ps1 原始输出与退出码 |
| `04-start.txt` | start.ps1 原始输出与退出码 |
| `05-status.txt` | status.ps1 原始输出与退出码 |
| `06-footer-check.txt` | 三页面截图存在性与包内静态资源检索结果 |
| `07-list.png` / `08-detail.png` / `09-create.png` | 8088 三页面截图（1280×1400，含页脚） |
| `10-5.1-api-regression.txt` | 5.1 接口回归原始输出（64/64） |
| `11-5.1-match-eval.txt` | /api/match 效果评估原始输出 |
| `12-match-request.json` / `13-match-response.json` | 冒烟请求与原始 UTF-8 响应 |
| `14-stop.txt` | stop.ps1 原始输出与退出码 |
| `15-http-checks.txt` | 三页面与 `/api/health` 状态码 |
| `16-code-docs-diff.txt` | 相对基线 `268edef` 的代码/文档 diff |
| `17-docx-check.txt` | docx 重导前后文本核对 |
| `18-ppt-check.txt` | PPT 文案检索结论与检索输出 |
| `99-summary.txt` | 全部检查项汇总 |

## 说明

- 构建：`deploy\build-package.ps1` 完整执行（Maven + npm ci + Vite build）；本机网络下直接下载 nginx 包不稳定，故用 `-NginxZip` 指向缓存中已按脚本内置 SHA256 校验通过的 `nginx-1.30.6.zip`，其余参数与文档一致。
- API 回归直接打 8088（Nginx 公共入口），同时覆盖反向代理链路。
- `docs/testing/7.3-windows-acceptance/`、`docs/testing/P3-seed-placeholder/` 等历史证据保持原样，未改。
- 本次改动范围仅 `web/`、`deploy/`、`docs/`；`server/`、`db/` 零改动。
