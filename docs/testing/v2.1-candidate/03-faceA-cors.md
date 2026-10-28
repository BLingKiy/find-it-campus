# 面 A：8088 部署 CORS

被测：解压后的候选包（`30002de`），Nginx 8088 → 127.0.0.1:18080，`review.mode=off`。

驱动：`raw/tools/faceA-cors.cjs`（Node fetch，经 8088）。原始结果：[`raw/faceA-cors.json`](raw/faceA-cors.json)。

| # | 请求 | HTTP | ACAO | ACAC | 观察 |
|---|---|---|---|---|---|
| 1 | `POST /api/records`，带 `Origin: http://127.0.0.1:8088` 和登录 Cookie | **200** | 无 | 无 | 创建 id=27；同源请求，不需要 CORS 头；**不再是 Z-83 的 403** |
| 2 | `POST /api/records`，无 `Origin` | **200** | 无 | 无 | 创建 id=28；非浏览器 CORS 路径正常 |
| 3 | `POST /api/records`，`Origin: http://localhost:5173` | **200** | `http://localhost:5173` | `true` | 白名单 loopback 跨源回显正确 |
| 4 | `POST /api/records`，`Origin: http://evil.example` | **403** | 无 | 无 | 响应体 `Invalid CORS request` |
| 5 | `OPTIONS /api/records`，`Origin: http://127.0.0.1:8088` + 预检头 | **200** | 无 | 无 | 同源预检，浏览器不会发；无 ACAO 属正确行为 |
| 6 | `OPTIONS /api/records`，`Origin: http://localhost:5173` + 预检头 | **200** | `http://localhost:5173` | `true` | `Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS` |

请求体（#1–#4 相同结构，仅 marker 不同）：

```json
{
  "type": "LOST",
  "title": "M6 CORS SAME 黑色钱包",
  "description": "M6 CORS SAME 在图书馆三楼丢失黑色皮质钱包，内有工行卡",
  "location": "图书馆三楼",
  "occurredAt": "2026-10-25 15:00:00",
  "contact": "13800000002",
  "publisherName": "演示乙"
}
```

响应（#1）关键片段：`{"code":0,"message":"已提交","data":{"id":27,...,"reviewState":"APPROVED",...}}`；响应头 `Vary: Cookie, Origin, Access-Control-Request-Method, Access-Control-Request-Headers`。

`nginx -t`（包内配置，prefix = 包根 `nginx\`）：

```
nginx: the configuration file ...\nginx\conf/nginx.conf syntax is ok
nginx: configuration file ...\nginx\conf/nginx.conf test is successful
EXIT=0
```

**结论：通过。** 8088 部署源 POST 成功且非 403；跨源 loopback 的 ACAO/ACAC 回显正确；外部 Origin 仍 403；`nginx -t` 通过。

> 口径说明：`Origin: http://127.0.0.1:8088` 与 `Host: 127.0.0.1:8088` 同源（`$http_host` 修复的直接结果），Spring 不把它当 CORS 请求，所以不输出 ACAO。把「同源必须回 ACAO」当判据会误判；本证据用 `localhost:5173` 的真实跨源请求单独证明 CORS 头。
