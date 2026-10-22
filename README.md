# find-it-campus · 校园失物招领

一条命令行跑起来的失物招领：寻物/招领启事发布、检索与语义匹配。
Vue3 + Vite 前端 + Spring Boot 3 + MyBatis + MySQL 后端。

> 本文档分工：前端段落（`web/` 的环境要求与启动命令）由 2.1-A 前端骨架任务写入，
> 数据库与后端段落由后端骨架任务（ZHYYKFX-13）写入；两条分支在 `feat/2.1-integration`
> 上合并后合成下面这一篇。

## 仓库结构

```
find-it-campus/
  README.md                本文档
  docs/                    架构与接口基线（事实源）
  web/                     Vue 3 + TypeScript + Vite 前端
  server/                  Spring Boot 后端（Maven Wrapper 自带，无需本机安装 Maven）
  db/                      建库与种子脚本（schema.sql / seed.sql / README.md）
```

技术依据与约定见 `docs/`：architecture-baseline.md（分层 / 错误码 / 环境）、api-contract.md（接口事实源）、ai-match-module.md（匹配模块边界）、frontend-baseline.md（前端结构基线）、open-questions.md（待拍板项）。

---

## 一、环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17**（Temurin 17.0.20.1 已实测） | Boot 3.5 的最低支持线；21 也可以 |
| Maven | **不需要本机安装** | 用 `server/` 自带的 Maven Wrapper（`mvnw` / `mvnw.cmd`） |
| MySQL | **8.0.x**（8.0.29 已实测） | FULLTEXT ngram、JSON 类型、CHECK 约束都要 8.x |
| Node.js | 22.x LTS（实测 v22.23.3 / npm 10.9.9） | 仅前端需要；Vite 8 要求 `^20.19.0 \|\| >=22.12.0` |

仓库依赖的实际锁定版本以 `web/package-lock.json` 为准。
版本基线的来源与取舍见 `docs/architecture-baseline.md` §9.1 与 ADR-016。

---

## 二、前端（`web/` 目录）

```bash
cd web
npm install
npm run dev          # http://localhost:5173 ，/api 经 Vite 代理转发到 http://localhost:18080
npm run build        # 产物 dist/
npm run preview      # 本地预览构建产物
npm run type-check   # vue-tsc --noEmit（src 与 vite.config.ts 两组配置）
```

环境变量：复制 `web/.env.example` 为 `web/.env.local` 后按需修改（`.env.local` 不提交）。默认 `VITE_API_BASE_URL=/api`，走 Vite 代理、浏览器视角同源无需 CORS；若改成 `http://localhost:18080/api` 则前端直连后端，依赖后端 CORS 配置（见 `docs/architecture-baseline.md` §9.4）。

> 演示用 dev server。生产打包部署不在本项目范围内。

---

## 三、数据库（先做这一步）

后端启动后要连 `find_it_campus` 库，所以**先建库再起服务**。三条命令：

```bash
# 1. 建库 + 建表（可重复执行，执行会清空已有数据）
mysql -u root -p -e "SOURCE db/schema.sql"

# 2. 导入种子数据（分类字典 9 条 + 演示记录 24 条）
mysql -u root -p find_it_campus -e "SOURCE db/seed.sql"

# 3. 重置演示数据（可选）
mysql -u root -p find_it_campus -e "SET FOREIGN_KEY_CHECKS=0; TRUNCATE t_match_result; TRUNCATE t_record; TRUNCATE t_category; SET FOREIGN_KEY_CHECKS=1;"
mysql -u root -p find_it_campus -e "SOURCE db/seed.sql"
```

Windows 上 `mysql` 不在 `PATH` 时写全路径，免密时去掉 `-p`，例如：

```bash
C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe -u root < db/schema.sql
C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe -u root find_it_campus < db/seed.sql
```

连接信息通过设置环境变量给后端，**密码不写进仓库**：

| 环境变量 | 含义 | 默认值 |
|---|---|---|
| `FINDIT_DB_URL` | JDBC URL | `jdbc:mysql://localhost:3306/find_it_campus?...` |
| `FINDIT_DB_USER` | 用户名 | `root` |
| `FINDIT_DB_PASSWORD` | 密码 | 空 |
| `FINDIT_MATCH_ENGINE` | `local` / `external` | `local` |
| `FINDIT_EMBED_BASE_URL` | 外部 embedding 服务地址 | 空 |
| `FINDIT_EMBED_API_KEY` | 外部 embedding 密钥 | 空 |

本机私密覆盖复制 `server/src/main/resources/application-example.yml` 为 `application-local.yml`
（该文件已被 `.gitignore` 忽略），再以 `--spring.profiles.active=local` 启动。

---

## 四、后端启动与构建（`server/` 目录）

Windows 用 `mvnw.cmd`，Linux / macOS / Git Bash 用 `./mvnw`：

```bash
cd server

./mvnw spring-boot:run              # 开发运行，http://localhost:18080/api/health
./mvnw -q clean package             # 构建，产物 server/target/find-it-campus-0.0.1-SNAPSHOT.jar
java -jar target/find-it-campus-0.0.1-SNAPSHOT.jar   # 直接跑 jar
```

启动成功日志末行为 `Started FindItCampusApplication in X seconds`，冒烟：

```bash
curl -i http://localhost:18080/api/health
# {"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"},"requestId":"…"}
```

> 默认端口是 **18080 而不是 8080**：开发机上的 8080 已被本机另一个服务（upload-svc）长期占用，
> 与其让每个人各配一次，不如把仓库里的默认端口直接对齐到 18080（前端 Vite 代理同步指向 18080）。
> 真要临时换端口：`./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=28080`。

所有接口统一返回 `{code,message,data,requestId}`；成功判据是 **HTTP 2xx 且 `code === 0`**，
错误码表见 `docs/architecture-baseline.md` §6.2。失败出口只有一个（`GlobalExceptionHandler`），
500 的响应体**不含堆栈**，只回 `message` 与 `requestId` —— 排障时用这个 id 去日志里 grep。

---

## 五、代理与 CORS

开发期有两条路，走第一条：

1. **默认走 Vite 代理**：`web/vite.config.ts` 里 `server.proxy['/api'] → http://localhost:18080`。
   浏览器视角同源，没有跨域问题，前端的 `VITE_API_BASE_URL` 默认就是 `/api`。
2. **后端 CORS 兜底**：给"直接访问 `http://localhost:18080`"或非代理消费者用。
   白名单写死 `http://localhost:5173` 与 `http://127.0.0.1:5173`，允许
   `GET,POST,PUT,PATCH,DELETE,OPTIONS`，`allowCredentials=false`，**不用 `*`**。

两条路都可能"看起来能用"，报错时先确认走的是哪条：改了前端 base URL 直指 18080，就走第 2 条。
选型的完整理由见 `docs/architecture-baseline.md` §9.4（ADR-012）。
