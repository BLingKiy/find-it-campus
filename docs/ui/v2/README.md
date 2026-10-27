# A3 v2 界面稿与证据

> 任务：ZHYYKFX-46 · A3 UI 基线补充
>
> 分支：`feat/v2-A3-ui-baseline`（从 `main @ 21784f6` 切出，不合并）
>
> 规范：`docs/ui-baseline.md` §11–§23

这组稿面覆盖 v2 的八个界面变化：登录、我的启事、通知、三档配对卡片、授权交互、匿名遮蔽、发布表单手机号提示、以及三档 × 四态矩阵。**不改 `web/` 一行代码**；`docs/ui/v2/` 下的 HTML 是设计稿源文件，不是产品代码。

## 稿面清单

| # | PNG | 视口 | 源文件 | 证明什么 |
|---|---|---|---|---|
| 01 | `login-states.png` | 1280 | `login-states.html` | 默认 / 凭证错误 / 锁定三态；无注册；错误不泄露账号是否存在 |
| 02 | `my-records.png` | 1280 | `my-records.html` | 分析中 / 已出结果 / 已降级；条数 = 配对数；已知限制三条 |
| 03 | `notifications.png` | 1280 | `notifications.html` | 未读红点 + 文字状态；全部标为已读 |
| 03b | `notifications-empty.png` | 1000 | `notifications-empty.html` | 空态：结论 + 原因 + 回列表 |
| 04 | `match-092.png` | 1280 | `match-092.html` | **0.92 高档：双端可直接看联系方式，无授权按钮** |
| 05 | `match-080.png` | 1280 | `match-080.html` | **0.80 中档：只有本人能公开自己的联系方式；对方“对方尚未公开”** |
| 06 | `match-060.png` | 1280 | `match-060.html` | **0.60 低档：只有条数，无标题/地点/联系方式/授权入口** |
| 07 | `authorize.png` | 1280 | `authorize.html` | 公开 / 撤回 + 附言 ≤200 + 超限提示 |
| 08 | `publish-phone.png` | 1280 | `publish-phone.html` | 手机号旁的“自行填写，请自行核实”；无短信验证 |
| 09 | `masked-board.png` | 1280（内含 390 / 320 两个真实视口） | `masked-board.html` + `masked-390.html` | 遮蔽占位、登录 CTA、320 无横向溢出 |
| 10 | `state-matrix.png` | 1280 | `state-matrix.html` | 三档 × 四态（正常/禁用/空/降级）12 格 |

`mockup.css` 是设计板与 v2 新原子的样式，**直接引用** `web/src/styles/{tokens,base,components}.css`，因此稿面的颜色、圆角、间距、按钮与 v1 同源；它不进入 `web/`。

## 复跑截图

要求：Windows + Microsoft Edge（本机 Edge 155）。截图是 1x，无滚动条。

```powershell
$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$dir  = 'docs/ui/v2'
$pages = @(
  @{ h = 'login-states.html';        p = 'login-states.png';        w = 1280; t = 1000 },
  @{ h = 'my-records.html';          p = 'my-records.png';          w = 1280; t = 1220 },
  @{ h = 'notifications.html';       p = 'notifications.png';       w = 1280; t = 860  },
  @{ h = 'notifications-empty.html'; p = 'notifications-empty.png'; w = 1000; t = 800  },
  @{ h = 'match-092.html';           p = 'match-092.png';           w = 1280; t = 1050 },
  @{ h = 'match-080.html';           p = 'match-080.png';           w = 1280; t = 1050 },
  @{ h = 'match-060.html';           p = 'match-060.png';           w = 1280; t = 960  },
  @{ h = 'authorize.html';           p = 'authorize.png';           w = 1280; t = 1040 },
  @{ h = 'publish-phone.html';       p = 'publish-phone.png';       w = 1280; t = 1260 },
  @{ h = 'masked-board.html';        p = 'masked-board.png';        w = 1280; t = 1360 },
  @{ h = 'state-matrix.html';        p = 'state-matrix.png';        w = 1280; t = 940  }
)
foreach ($p in $pages) {
  $uri = 'file:///' + ((Resolve-Path "$dir/$($p.h)").Path -replace '\\','/')
  & $edge --headless=new --disable-gpu --no-first-run --no-default-browser-check `
    --hide-scrollbars --force-device-scale-factor=1 `
    --user-data-dir="$PWD/.edge-profile" `
    --window-size="$($p.w),$($p.t)" `
    --screenshot="$PWD/$dir/$($p.p)" $uri
}
```

> 注意：无头 Edge 的窗口最小宽度约 500px；`masked-board.html` 用两个 `<iframe>`（390 / 320）得到真实的小视口，而不是把 1280 稿缩小。直接用 `--window-size=390` 截 `masked-390.html` 会得到 492px 的布局视口 + 390px 的裁切图，不能作为移动端证据。

## 文案检索

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/ui/v2/check-copy.ps1
```

脚本用 Unicode 码点拼出两个禁用短语，扫描 `docs/ui-baseline.md` 与 `docs/ui/v2/**`，输出禁用短语 0 处、“可能相关”>0、结论 PASS。脚本本身不包含连续禁用串，避免自查命中。运行结果见 `copy-check.txt`。

## 验收映射

| 验收项 | 证据 |
|---|---|
| 0.92 / 0.80 / 0.60 各自落到对应档位，文案与可用按钮正确 | `match-092.png` / `match-080.png` / `match-060.png`；规范 §15.4–§15.6 |
| 候选描述只用“可能相关” | `copy-check.txt`；规范 §18 |
| 手机号旁有“自行填写，请自行核实” | `publish-phone.png`；规范 §19.2 |
| 已知限制三条齐全 | `my-records.png` 底部；规范 §19.1 |
| 不改代码：`web/` 0 行 | `git diff --stat origin/main...HEAD -- web/` 为空 |

## 边界

- 静态稿：按钮不连接口，不倒计时、不发请求；证明的是视觉、状态与文案。
- 示例数据全部虚构；手机号使用 `138/139 0000` 号段，不含真实个人信息。
- 没有 v2 的 LCP/INP/CLS、包体积、axe 或真机数据；实现后按 `docs/ui-baseline.md` §20.5 测，结果写进 E2/D2 的交付评论。
- 没有 768 / 1120 的单独截图；这两档规则见 §20.3，靠实现后的响应式断言验证。
- 深色模式未做，只对浅色对比度负责。
