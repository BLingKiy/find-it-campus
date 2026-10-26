# =====================================================================
# find-it-campus · Windows 停止脚本
#
# 顺序：nginx -s quit（失败再按 PID）→ 按 runtime\backend.pid 停后端
#       → 等 8088/18080 释放。幂等；只停本包 PID/本包路径的进程。
# 不注册服务、不调用管理员命令。
# =====================================================================

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1')
Initialize-ConsoleEncoding
Set-ScriptLog 'stop'

$pkgRoot = Get-PackageRoot
$nginxExe = Join-Path $pkgRoot 'nginx\nginx.exe'
$nginxPrefix = Join-Path $pkgRoot 'nginx'
$nginxConfig = Join-Path $nginxPrefix 'conf\nginx.conf'
$backendPidFile = Join-Path $pkgRoot 'runtime\backend.pid'
$nginxPidFile = Join-Path $pkgRoot 'runtime\nginx.pid'
$jarPath = Join-Path $pkgRoot 'app\find-it-campus-0.0.1-SNAPSHOT.jar'

$exitCode = 0

function Stop-PackageNginx {
    $result = [pscustomobject]@{
        Component = 'Nginx'
        Result    = '未运行'
        Detail    = ''
    }

    $info = Get-PidFileProcess -PidFile $nginxPidFile -ExpectedExePath $nginxExe -CommandLineNeedle $nginxExe
    $procId = 0
    if ($info.Alive -and $info.Matches) {
        $procId = $info.Pid
    } else {
        if ($info.PidFileExists) {
            Write-Log ('忽略不可用的 nginx.pid：' + $info.Reason) 'WARN'
            Remove-Item -LiteralPath $nginxPidFile -Force -ErrorAction SilentlyContinue
        }
        $found = @(Find-PackageProcess -ExpectedExePath $nginxExe -CommandLineNeedle $nginxExe)
        if ($found.Count -gt 0) {
            $procId = [int]$found[0].ProcessId
            Write-Log ('未找到有效 PID 文件，但发现本包 Nginx 进程 PID {0}，继续按路径校验后停止。' -f $procId) 'WARN'
        }
    }

    if ($procId -le 0) {
        $result.Detail = 'PID 文件不存在或进程未运行'
        Write-Log 'Nginx 未运行（幂等）。' 'OK'
        return $result
    }

    $details = Get-ProcessDetails -ProcessId $procId
    if (-not (Test-ProcessMatches -Details $details -ExpectedExePath $nginxExe -CommandLineNeedle $nginxExe)) {
        $result.Result = '拒绝操作'
        $result.Detail = ('PID {0} 与部署包 Nginx 路径不匹配' -f $procId)
        Write-Log $result.Detail 'ERROR'
        return $result
    }

    Write-Log ('停止 Nginx：先执行 nginx -s quit（PID {0}）' -f $procId)
    $quitOk = $false
    if (Test-Path -LiteralPath $nginxConfig -PathType Leaf) {
        try {
            & $nginxExe -p ("{0}/" -f $nginxPrefix) -c 'conf/nginx.conf' -s quit 2>&1 | ForEach-Object { Write-Log ('nginx | ' + $_) }
            $quitOk = ($LASTEXITCODE -eq 0)
        } catch {
            Write-Log ('nginx -s quit 失败：' + $_.Exception.Message) 'WARN'
        }
    } else {
        Write-Log ('缺少 nginx.conf，跳过 -s quit，直接按 PID 停止。') 'WARN'
    }

    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        $current = Get-ProcessDetails -ProcessId $procId
        if (-not $current.Exists) { break }
        Start-Sleep -Milliseconds 500
    }

    $current = Get-ProcessDetails -ProcessId $procId
    if ($current.Exists) {
        Write-Log ('nginx -s quit 后进程仍存在，按已校验 PID {0} 停止。' -f $procId) 'WARN'
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }

    # 兜底清理该包 nginx.exe 的残留 worker（只匹配本包可执行文件路径）。
    $remaining = @(Find-PackageProcess -ExpectedExePath $nginxExe -CommandLineNeedle $nginxExe)
    foreach ($item in $remaining) {
        if ([int]$item.ProcessId -eq $procId) { continue }
        Write-Log ('清理本包残留 Nginx 进程 PID {0}' -f $item.ProcessId) 'WARN'
        Stop-Process -Id ([int]$item.ProcessId) -Force -ErrorAction SilentlyContinue
    }

    Remove-Item -LiteralPath $nginxPidFile -Force -ErrorAction SilentlyContinue
    $result.Result = '已停止'
    $result.Detail = if ($quitOk) { 'nginx -s quit 成功' } else { '按 PID 停止' }
    Write-Log ('Nginx 已停止（{0}）。' -f $result.Detail) 'OK'
    return $result
}

function Stop-PackageBackend {
    $result = [pscustomobject]@{
        Component = '后端'
        Result    = '未运行'
        Detail    = ''
    }

    $info = Get-PidFileProcess -PidFile $backendPidFile -ExpectedExePath '' -CommandLineNeedle $jarPath
    $procId = 0
    if ($info.Alive -and $info.Matches) {
        $procId = $info.Pid
    } else {
        if ($info.PidFileExists) {
            Write-Log ('忽略不可用的 backend.pid：' + $info.Reason) 'WARN'
            Remove-Item -LiteralPath $backendPidFile -Force -ErrorAction SilentlyContinue
        }
        $found = @(Find-PackageProcess -ExpectedExePath '' -CommandLineNeedle $jarPath)
        if ($found.Count -gt 0) {
            $procId = [int]$found[0].ProcessId
            Write-Log ('未找到有效 PID 文件，但发现本包后端进程 PID {0}，继续按命令行校验后停止。' -f $procId) 'WARN'
        }
    }

    if ($procId -le 0) {
        $result.Detail = 'PID 文件不存在或进程未运行'
        Write-Log '后端未运行（幂等）。' 'OK'
        return $result
    }

    $details = Get-ProcessDetails -ProcessId $procId
    if (-not (Test-ProcessMatches -Details $details -ExpectedExePath '' -CommandLineNeedle $jarPath)) {
        $result.Result = '拒绝操作'
        $result.Detail = ('PID {0} 命令行不包含本包 jar 路径' -f $procId)
        Write-Log $result.Detail 'ERROR'
        return $result
    }

    Write-Log ('停止后端：PID {0}' -f $procId)
    Stop-Process -Id $procId -ErrorAction SilentlyContinue

    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        $current = Get-ProcessDetails -ProcessId $procId
        if (-not $current.Exists) { break }
        Start-Sleep -Milliseconds 500
    }

    $current = Get-ProcessDetails -ProcessId $procId
    if ($current.Exists) {
        Write-Log ('后端 PID {0} 未在 20 秒内退出，执行强制终止。' -f $procId) 'WARN'
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }

    Remove-Item -LiteralPath $backendPidFile -Force -ErrorAction SilentlyContinue
    $result.Result = '已停止'
    $result.Detail = ('PID {0}；Windows 无服务控制，JVM 为进程终止（非优雅 drain）' -f $procId)
    Write-Log ('后端已停止（PID {0}）。' -f $procId) 'OK'
    return $result
}

try {
    Write-Section 'find-it-campus Windows 停止'
    Write-Log ('部署包目录：' + $pkgRoot)

    if (-not (Test-Path -LiteralPath $nginxExe -PathType Leaf)) {
        Write-Log ('Nginx 可执行文件不存在：' + $nginxExe) 'WARN'
    }
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        Write-Log ('后端 jar 不存在：' + $jarPath) 'WARN'
    }

    $nginxResult = Stop-PackageNginx
    $backendResult = Stop-PackageBackend

    Write-Section '等待端口释放'
    $port8088Free = Wait-PortFree -Port 8088 -TimeoutSec 20
    $port18080Free = Wait-PortFree -Port 18080 -TimeoutSec 20

    if (-not $port8088Free) {
        $owners = @(Get-PortOwners -Port 8088)
        $text = ($owners | ForEach-Object { 'PID {0}/{1}' -f $_.ProcessId, $_.Name }) -join ', '
        Write-Log ('8088 仍未释放：' + $text) 'ERROR'
        $exitCode = 1
    } else {
        Write-Log '8088 已释放' 'OK'
    }

    if (-not $port18080Free) {
        $owners = @(Get-PortOwners -Port 18080)
        $text = ($owners | ForEach-Object { 'PID {0}/{1}' -f $_.ProcessId, $_.Name }) -join ', '
        Write-Log ('18080 仍未释放：' + $text) 'ERROR'
        $exitCode = 1
    } else {
        Write-Log '18080 已释放' 'OK'
    }

    Write-Section '停止结果'
    $stopTable = @($backendResult, $nginxResult) | Format-Table -AutoSize | Out-String
    [Console]::Out.Write($stopTable)
    if ($script:LogFile) {
        Add-Content -LiteralPath $script:LogFile -Value $stopTable -Encoding UTF8
    }
    Write-Log ('后端：{0}；Nginx：{1}；8088 释放={2}；18080 释放={3}' -f $backendResult.Result, $nginxResult.Result, $port8088Free, $port18080Free)
    Write-Log ('停止日志：' + (Get-ScriptLogPath))
    if ($exitCode -eq 0) {
        Write-Log 'RESULT: STOPPED' 'OK'
    } else {
        Write-Log 'RESULT: PORT_NOT_RELEASED' 'ERROR'
    }
    exit $exitCode
} catch {
    Write-Log ('停止失败：' + $_.Exception.Message) 'ERROR'
    if ($_.ScriptStackTrace) { Write-Log ('调用栈：' + $_.ScriptStackTrace) 'ERROR' }
    if ($script:LogFile) { Write-Log ('详细日志：' + $script:LogFile) 'ERROR' }
    exit 1
}
