# =====================================================================
# find-it-campus · Windows 状态检查
#
# 输出组件表：后端 / Nginx / MySQL 连通性 / 8088 健康 / 监听端口 / PID / 最近日志。
# 健康判据统一走 http://127.0.0.1:8088/api/health。
# 全健康退出码 0；否则非 0（未安装返回 2）。
# =====================================================================

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1')
Initialize-ConsoleEncoding
Set-ScriptLog 'status'

$pkgRoot = Get-PackageRoot
$backendUrl = 'http://127.0.0.1:18080/api/health'
$publicUrl = 'http://127.0.0.1:8088/'
$publicHealthUrl = 'http://127.0.0.1:8088/api/health'
$jarPath = Join-Path $pkgRoot 'app\find-it-campus-0.0.1-SNAPSHOT.jar'
$nginxExe = Join-Path $pkgRoot 'nginx\nginx.exe'
$backendPidFile = Join-Path $pkgRoot 'runtime\backend.pid'
$nginxPidFile = Join-Path $pkgRoot 'runtime\nginx.pid'
$backendLog = Join-Path $pkgRoot 'logs\backend.log'
$backendErrLog = Join-Path $pkgRoot 'logs\backend-error.log'
$nginxErrorLog = Join-Path $pkgRoot 'logs\nginx-error.log'
$nginxAccessLog = Join-Path $pkgRoot 'logs\nginx-access.log'

function Get-RelativeLogPath {
    param([string]$Path)
    if (-not $Path) { return '-' }
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        return $Path.Substring($pkgRoot.Length).TrimStart('\')
    }
    return '-'
}

function Get-LatestLog {
    param([string]$Pattern)
    $files = @(Get-ChildItem -LiteralPath (Join-Path $pkgRoot 'logs') -Filter $Pattern -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
    if ($files.Count -eq 0) { return '-' }
    return $files[0].FullName.Substring($pkgRoot.Length).TrimStart('\')
}

try {
    Write-Section 'find-it-campus Windows 状态'

    $configPath = Join-Path $pkgRoot 'config\deploy.env.ps1'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        Write-Log '未安装：缺少 config\deploy.env.ps1。请先运行 scripts\install.ps1。' 'ERROR'
        [Console]::Out.WriteLine('')
        $notInstalledTable = @([pscustomobject]@{ 组件 = '安装'; 状态 = 'NOT_INSTALLED'; 详情 = '缺少 config\deploy.env.ps1'; PID = '-'; 最近日志 = '-' }) |
            Format-Table -AutoSize | Out-String
        [Console]::Out.Write($notInstalledTable)
        Write-Log 'RESULT: NOT_INSTALLED' 'ERROR'
        exit 2
    }

    . $configPath
    if (-not $env:FINDIT_DB_URL) {
        throw 'config\deploy.env.ps1 未设置 FINDIT_DB_URL。'
    }

    $rows = @()
    $problems = @()

    # ---------- 后端 ----------
    $backendInfo = Get-PidFileProcess -PidFile $backendPidFile -ExpectedExePath '' -CommandLineNeedle $jarPath
    $backendPid = 0
    $backendState = 'DOWN'
    $backendDetail = '未运行'
    if ($backendInfo.Alive -and $backendInfo.Matches) {
        $backendPid = $backendInfo.Pid
    } elseif (@(Find-PackageProcess -ExpectedExePath '' -CommandLineNeedle $jarPath).Count -gt 0) {
        $backendPid = [int](@(Find-PackageProcess -ExpectedExePath '' -CommandLineNeedle $jarPath)[0].ProcessId)
    }

    $backendHealth = Get-HealthSnapshot -Url $backendUrl -TimeoutSec 5
    if ($backendPid -gt 0) {
        if ($backendHealth.HttpOk -and $backendHealth.Code -eq 0 -and $backendHealth.Status -eq 'UP' -and $backendHealth.Db -eq 'UP') {
            $backendState = 'UP'
            $backendDetail = '127.0.0.1:18080 / db=UP / engine={0}' -f $backendHealth.Engine
        } else {
            $backendState = 'DEGRADED'
            $backendDetail = 'PID 在，但健康检查失败：{0}' -f ($(if ($backendHealth.Error) { $backendHealth.Error } else { 'code={0}, status={1}, db={2}' -f $backendHealth.Code, $backendHealth.Status, $backendHealth.Db }))
            $problems += '后端健康检查失败'
        }
    } else {
        if ($backendHealth.HttpOk) {
            $backendDetail = '未找到本包 PID，但 18080 有健康响应'
        }
        $problems += '后端未运行'
    }
    $rows += [pscustomobject]@{
        组件     = '后端'
        状态     = $backendState
        详情     = $backendDetail
        PID      = $(if ($backendPid -gt 0) { [string]$backendPid } else { '-' })
        最近日志 = (Get-RelativeLogPath $backendLog)
    }

    # ---------- Nginx ----------
    $nginxInfo = Get-PidFileProcess -PidFile $nginxPidFile -ExpectedExePath $nginxExe -CommandLineNeedle $nginxExe
    $nginxPid = 0
    $nginxState = 'DOWN'
    $nginxDetail = '未运行'
    if ($nginxInfo.Alive -and $nginxInfo.Matches) {
        $nginxPid = $nginxInfo.Pid
    } else {
        $foundNginx = @(Find-PackageProcess -ExpectedExePath $nginxExe -CommandLineNeedle $nginxExe)
        if ($foundNginx.Count -gt 0) { $nginxPid = [int]$foundNginx[0].ProcessId }
    }
    if ($nginxPid -gt 0) {
        $nginxState = 'UP'
        $nginxDetail = '8088 / nginx 1.30.6'
    } else {
        $problems += 'Nginx 未运行'
    }
    $rows += [pscustomobject]@{
        组件     = 'Nginx'
        状态     = $nginxState
        详情     = $nginxDetail
        PID      = $(if ($nginxPid -gt 0) { [string]$nginxPid } else { '-' })
        最近日志 = (Get-RelativeLogPath $nginxErrorLog)
    }

    # ---------- MySQL ----------
    $mysqlState = 'FAIL'
    $mysqlDetail = ''
    $mysqlVersion = ''
    $mysqlExe = $null
    try {
        $mysqlExe = Resolve-MysqlExe -MysqlBin $DeployMysqlBin
    } catch {
        $mysqlExe = $null
    }
    if (-not (Test-TcpPort -ComputerName $DeployDbHost -Port $DeployDbPort -TimeoutMs 2000)) {
        $mysqlDetail = ('{0}:{1} 无法建立 TCP 连接' -f $DeployDbHost, $DeployDbPort)
        $problems += 'MySQL 端口不可达'
    } elseif (-not $mysqlExe) {
        $mysqlState = 'WARN'
        $mysqlDetail = ('TCP 可达，但未找到 mysql.exe，无法校验版本')
        $problems += 'MySQL 客户端缺失，无法校验版本'
    } else {
        $optionFile = $null
        try {
            $optionFile = New-MysqlOptionFile -DbHost $DeployDbHost -DbPort $DeployDbPort -DbUser $DeployDbUser -DbPassword $env:FINDIT_DB_PASSWORD
            $mysqlVersion = (Invoke-MysqlScalar -MysqlExe $mysqlExe -OptionFile $optionFile -Query 'SELECT VERSION();').Trim()
            if ($mysqlVersion -match '^8\.') {
                $mysqlState = 'OK'
                $mysqlDetail = ('{0}:{1} / MySQL {2}' -f $DeployDbHost, $DeployDbPort, $mysqlVersion)
            } else {
                $mysqlState = 'FAIL'
                $mysqlDetail = ('MySQL 版本不是 8.x：{0}' -f $mysqlVersion)
                $problems += 'MySQL 版本不符'
            }
        } catch {
            $mysqlState = 'FAIL'
            $mysqlDetail = ('连接/查询失败：' + $_.Exception.Message)
            $problems += 'MySQL 连通性或认证失败'
        } finally {
            Remove-MysqlOptionFile -Path $optionFile
        }
    }
    $rows += [pscustomobject]@{
        组件     = 'MySQL'
        状态     = $mysqlState
        详情     = $mysqlDetail
        PID      = '-'
        最近日志 = '-'
    }

    # ---------- 8088 健康（统一判据） ----------
    $publicHealth = Get-HealthSnapshot -Url $publicHealthUrl -TimeoutSec 5
    $publicState = 'FAIL'
    $publicDetail = ''
    if ($publicHealth.HttpOk -and $publicHealth.Code -eq 0 -and $publicHealth.Status -eq 'UP' -and $publicHealth.Db -eq 'UP') {
        $publicState = 'UP'
        $publicDetail = 'code=0 / status=UP / db=UP / engine={0}' -f $publicHealth.Engine
    } else {
        if ($publicHealth.Error) {
            $publicDetail = $publicHealth.Error
        } else {
            $publicDetail = 'code={0}, status={1}, db={2}' -f $publicHealth.Code, $publicHealth.Status, $publicHealth.Db
        }
        $problems += '8088 健康检查未通过'
    }
    $rows += [pscustomobject]@{
        组件     = '8088 健康'
        状态     = $publicState
        详情     = $publicDetail
        PID      = '-'
        最近日志 = (Get-RelativeLogPath $nginxAccessLog)
    }

    # ---------- 监听端口 ----------
    $port8088Ok = $false
    $port8088 = @(Get-PortOwners -Port 8088)
    if ($port8088.Count -eq 1 -and $nginxPid -gt 0) {
        $owner = $port8088[0]
        $ownerOk = ($owner.ProcessId -eq $nginxPid)
        if (-not $ownerOk) {
            # Windows 上监听 8088 的通常是 nginx worker，nginx.pid 记的是 master。
            $ownerDetails = Get-ProcessDetails -ProcessId $owner.ProcessId
            $ownerOk = Test-ProcessMatches -Details $ownerDetails -ExpectedExePath $nginxExe -CommandLineNeedle ''
        }
        if ($ownerOk) {
            $port8088Ok = $true
            $rows += [pscustomobject]@{ 组件 = '8088 监听'; 状态 = 'OK'; 详情 = '监听 PID {0} / {1}（master {2}）' -f $owner.ProcessId, $owner.Name, $nginxPid; PID = [string]$owner.ProcessId; 最近日志 = '-' }
        } else {
            $rows += [pscustomobject]@{ 组件 = '8088 监听'; 状态 = 'CONFLICT'; 详情 = '被非本包进程占用：PID {0} / {1}' -f $owner.ProcessId, $owner.Name; PID = [string]$owner.ProcessId; 最近日志 = '-' }
            $problems += '8088 被非本包进程占用'
        }
    } elseif ($port8088.Count -gt 0) {
        $text = ($port8088 | ForEach-Object { 'PID {0}/{1}' -f $_.ProcessId, $_.Name }) -join ', '
        $rows += [pscustomobject]@{ 组件 = '8088 监听'; 状态 = 'CONFLICT'; 详情 = ('被非本包进程占用：' + $text); PID = [string]$port8088[0].ProcessId; 最近日志 = '-' }
        $problems += '8088 被非本包进程占用'
    } else {
        $rows += [pscustomobject]@{ 组件 = '8088 监听'; 状态 = 'DOWN'; 详情 = '无监听'; PID = '-'; 最近日志 = '-' }
        $problems += '8088 无监听'
    }

    $port18080Ok = $false
    $port18080 = @(Get-PortOwners -Port 18080)
    if ($port18080.Count -eq 1 -and $backendPid -gt 0 -and $port18080[0].ProcessId -eq $backendPid) {
        $port18080Ok = $true
        $rows += [pscustomobject]@{ 组件 = '18080 监听'; 状态 = 'OK'; 详情 = 'PID {0} / {1}' -f $port18080[0].ProcessId, $port18080[0].Name; PID = [string]$port18080[0].ProcessId; 最近日志 = '-' }
    } elseif ($port18080.Count -gt 0) {
        $text = ($port18080 | ForEach-Object { 'PID {0}/{1}' -f $_.ProcessId, $_.Name }) -join ', '
        $rows += [pscustomobject]@{ 组件 = '18080 监听'; 状态 = 'CONFLICT'; 详情 = ('被非本包进程占用：' + $text); PID = [string]$port18080[0].ProcessId; 最近日志 = '-' }
        $problems += '18080 被非本包进程占用'
    } else {
        $rows += [pscustomobject]@{ 组件 = '18080 监听'; 状态 = 'DOWN'; 详情 = '无监听'; PID = '-'; 最近日志 = '-' }
        $problems += '18080 无监听'
    }

    [Console]::Out.WriteLine('')
    $tableText = $rows | Format-Table -AutoSize | Out-String
    [Console]::Out.Write($tableText)
    if ($script:LogFile) {
        Add-Content -LiteralPath $script:LogFile -Value $tableText -Encoding UTF8
    }

    $latestInstall = Get-LatestLog -Pattern 'install-*.log'
    $latestStart = Get-LatestLog -Pattern 'start-*.log'
    $latestStop = Get-LatestLog -Pattern 'stop-*.log'
    [Console]::Out.WriteLine(('日志：安装={0}；启动={1}；停止={2}' -f $latestInstall, $latestStart, $latestStop))
    [Console]::Out.WriteLine(('进程：后端 PID={0}；Nginx PID={1}' -f $(if ($backendPid -gt 0) { $backendPid } else { '-' }), $(if ($nginxPid -gt 0) { $nginxPid } else { '-' })))
    [Console]::Out.WriteLine(('健康：8088={0}；18080={1}' -f $publicState, $backendState))

    $healthy = ($backendState -eq 'UP') -and ($nginxState -eq 'UP') -and ($mysqlState -eq 'OK') -and ($publicState -eq 'UP') -and
               $port8088Ok -and $port18080Ok -and
               ($nginxPid -gt 0) -and ($backendPid -gt 0)

    if (-not $healthy) {
        [Console]::Out.WriteLine('')
        [Console]::Out.WriteLine('最近错误日志：')
        foreach ($line in (Get-FileTail -Path $backendErrLog -Lines 8)) { [Console]::Out.WriteLine('backend-error | ' + $line) }
        foreach ($line in (Get-FileTail -Path $nginxErrorLog -Lines 8)) { [Console]::Out.WriteLine('nginx-error | ' + $line) }
        [Console]::Out.WriteLine('问题：' + (($problems | Select-Object -Unique) -join '；'))
    }

    if ($healthy) {
        Write-Log 'RESULT: HEALTHY' 'OK'
        exit 0
    } else {
        Write-Log 'RESULT: UNHEALTHY' 'ERROR'
        exit 1
    }
} catch {
    Write-Log ('状态检查失败：' + $_.Exception.Message) 'ERROR'
    if ($_.ScriptStackTrace) { Write-Log ('调用栈：' + $_.ScriptStackTrace) 'ERROR' }
    if ($script:LogFile) { Write-Log ('详细日志：' + $script:LogFile) 'ERROR' }
    exit 1
}
