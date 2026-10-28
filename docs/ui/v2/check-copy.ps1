# A3 v2 文案一致性检查（只读脚本，不改任何文件）。
#
# 目的：证明设计稿与规范里没有出现两个被禁止的候选称呼，也没有旧状态词。
# 做法：把禁用短语/状态词用 Unicode 码点拼出来，避免脚本自身成为命中项；
#       扫描 docs/ui-baseline.md 与 docs/ui/v2/ 下的文本文件。
#       copy-check.txt 是脚本自己的输出，排除在扫描集之外，避免自计数。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File docs/ui/v2/check-copy.ps1

$ErrorActionPreference = 'Stop'

$docDir = Split-Path -Parent $PSScriptRoot          # docs/ui
$outline = Join-Path $docDir 'ui-baseline.md'       # docs/ui-baseline.md
$v2Dir = $PSScriptRoot                              # docs/ui/v2

# 候选描述的禁用近义写法
$termA = -join ([char[]](0x53EF, 0x80FD, 0x5339, 0x914D))
$termB = -join ([char[]](0x7591, 0x4F3C, 0x5339, 0x914D))
$termOk = -join ([char[]](0x53EF, 0x80FD, 0x76F8, 0x5173))

# 旧状态词：作废的英文枚举 + 两个旧中文状态标签
$termC = -join ([char[]](0x43, 0x4C, 0x41, 0x49, 0x4D, 0x45, 0x44))
$termD = -join ([char[]](0x5DF2, 0x8BA4, 0x9886))
$termE = -join ([char[]](0x5F85, 0x8BA4, 0x9886))
$statusTerms = @(
    @{ Key = 'C1'; Term = $termC },
    @{ Key = 'C2'; Term = $termD },
    @{ Key = 'C3'; Term = $termE }
)

$files = @()
if (Test-Path -LiteralPath $outline) {
    $files += Get-Item -LiteralPath $outline
}
$files += Get-ChildItem -LiteralPath $v2Dir -File |
    Where-Object {
        $_.Extension -in '.md', '.html', '.css', '.txt', '.svg' -and
        $_.Name -ne 'copy-check.txt'
    } |
    Sort-Object Name

$hitsA = 0
$hitsB = 0
$hitsOk = 0
$detailA = @()
$detailB = @()
$statusHits = @{}
$statusDetail = @{}
foreach ($st in $statusTerms) {
    $statusHits[$st.Key] = 0
    $statusDetail[$st.Key] = @()
}

foreach ($file in $files) {
    $text = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    $countA = ([regex]::Matches($text, [regex]::Escape($termA))).Count
    $countB = ([regex]::Matches($text, [regex]::Escape($termB))).Count
    $hitsOk += ([regex]::Matches($text, [regex]::Escape($termOk))).Count
    $hitsA += $countA
    $hitsB += $countB
    if ($countA -gt 0) { $detailA += "$($file.Name): $countA" }
    if ($countB -gt 0) { $detailB += "$($file.Name): $countB" }

    foreach ($st in $statusTerms) {
        $count = ([regex]::Matches($text, [regex]::Escape($st.Term))).Count
        if ($count -gt 0) {
            $statusHits[$st.Key] += $count
            $statusDetail[$st.Key] += "$($file.Name): $count"
        }
    }
}

"扫描文件数: $($files.Count)"
"禁用短语 A: $hitsA"
"禁用短语 B: $hitsB"
"状态词 C1（旧枚举）: $($statusHits['C1'])"
"状态词 C2（旧中文标签一）: $($statusHits['C2'])"
"状态词 C3（旧中文标签二）: $($statusHits['C3'])"
"可能相关: $hitsOk"
if ($detailA.Count -gt 0) { "A 命中文件:"; $detailA | ForEach-Object { "  $_" } }
if ($detailB.Count -gt 0) { "B 命中文件:"; $detailB | ForEach-Object { "  $_" } }
foreach ($st in $statusTerms) {
    if ($statusHits[$st.Key] -gt 0) {
        "$($st.Key) 命中文件:"
        $statusDetail[$st.Key] | ForEach-Object { "  $_" }
    }
}

$statusTotal = ($statusTerms | ForEach-Object { $statusHits[$_.Key] } | Measure-Object -Sum).Sum

if ($hitsA -eq 0 -and $hitsB -eq 0 -and $statusTotal -eq 0 -and $hitsOk -gt 0) {
    "结论: PASS"
    return
}

"结论: FAIL"
throw 'copy-check failed'
