# A3 v2 文案一致性检查（只读脚本，不改任何文件）。
#
# 目的：证明设计稿与规范里没有出现两个被禁止的候选称呼。
# 做法：把禁用短语用 Unicode 码点拼出来，避免脚本自身成为命中项；
#       扫描 docs/ui-baseline.md 与 docs/ui/v2/ 下的文本文件。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File docs/ui/v2/check-copy.ps1

$ErrorActionPreference = 'Stop'

$docDir = Split-Path -Parent $PSScriptRoot          # docs/ui
$outline = Join-Path $docDir 'ui-baseline.md'       # docs/ui-baseline.md
$v2Dir = $PSScriptRoot                              # docs/ui/v2

$termA = -join ([char[]](0x53EF, 0x80FD, 0x5339, 0x914D))
$termB = -join ([char[]](0x7591, 0x4F3C, 0x5339, 0x914D))
$termOk = -join ([char[]](0x53EF, 0x80FD, 0x76F8, 0x5173))

$files = @()
if (Test-Path -LiteralPath $outline) {
    $files += Get-Item -LiteralPath $outline
}
$files += Get-ChildItem -LiteralPath $v2Dir -File |
    Where-Object { $_.Extension -in '.md', '.html', '.css', '.txt', '.svg' } |
    Sort-Object Name

$hitsA = 0
$hitsB = 0
$hitsOk = 0
$detailA = @()
$detailB = @()

foreach ($file in $files) {
    $text = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    $countA = ([regex]::Matches($text, [regex]::Escape($termA))).Count
    $countB = ([regex]::Matches($text, [regex]::Escape($termB))).Count
    $hitsOk += ([regex]::Matches($text, [regex]::Escape($termOk))).Count
    $hitsA += $countA
    $hitsB += $countB
    if ($countA -gt 0) { $detailA += "$($file.Name): $countA" }
    if ($countB -gt 0) { $detailB += "$($file.Name): $countB" }
}

"扫描文件数: $($files.Count)"
"禁用短语 A: $hitsA"
"禁用短语 B: $hitsB"
"可能相关: $hitsOk"
if ($detailA.Count -gt 0) { "A 命中文件:"; $detailA | ForEach-Object { "  $_" } }
if ($detailB.Count -gt 0) { "B 命中文件:"; $detailB | ForEach-Object { "  $_" } }

if ($hitsA -eq 0 -and $hitsB -eq 0 -and $hitsOk -gt 0) {
    "结论: PASS"
    return
}

"结论: FAIL"
throw 'copy-check failed'
