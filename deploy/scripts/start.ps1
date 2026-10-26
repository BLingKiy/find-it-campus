# =====================================================================
# find-it-campus · Windows 启动脚本
#
# 启动顺序：后端 java -jar（127.0.0.1:18080）→ 等 /api/health UP/DB UP
#           → nginx.exe（8088，托管 web/dist 并反代 /api）→ 校验 8088。
# 幂等：已启动不重复拉起；端口被非本包进程占用时明确报错并给出 PID。
# 不注册服务、不调用管理员命令。
# =====================================================================

[CmdletBinding()]
param(
    [string]$JavaBin = '',
    [int]$TimeoutSec = 60
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1')
Initialize-ConsoleEncoding
Set-ScriptLog 'start'

$pkgRoot = Get-PackageRoot
$backendUrl = 'http://127.0.0.1:18080/api/health'
$publicUrl = 'http://127.0.0.1:8088/'
$publicHealthUrl = 'http://127.0.0.1:8088/api/health'

$startedBackend = $false
$startedNginx = $false
$backendPid = 0

function Wait-BackendHealthy {
    param([int]$Seconds, [System.Diagnostics.Process]$Process)

    $deadline = (Get-Date).AddSeconds($Seconds)
    $nextReport = Get-Date
    $lastSnapshot = $null
    while ((Get-Date) -lt $deadline) {
        if ($Process -and $Process.HasExited) {
            return [pscustomobject]@{ Ok = $false; Snapshot = $lastSnapshot; Reason = ('后端进程已退出，退出码 {0}' -f $Process.ExitCode) }
        }

        $snapshot = Get-HealthSnapshot -Url $backendUrl -TimeoutSec 3
        $lastSnapshot = $snapshot
        if ($snapshot.HttpOk -and $snapshot.Code -eq 0 -and $snapshot.Status -eq 'UP' -and $snapshot.Db -eq 'UP') {
            return [pscustomobject]@{ Ok = $true; Snapshot = $snapshot; Reason = '' }
        }

        if ((Get-Date) -ge $nextReport) {
            $detail = if ($snapshot.Error) { $snapshot.Error } else { ('HTTP {0}, code={1}, status={2}, db={3}' -f $snapshot.StatusCode, $snapshot.Code, $snapshot.Status, $snapshot.Db) }
            Write-Log ('等待后端健康检查：' + $detail)
            $nextReport = (Get-Date).AddSeconds(10)
        }
        Start-Sleep -Seconds 2
    }
    return [pscustomobject]@{ Ok = $false; Snapshot = $lastSnapshot; Reason = ('超过 {0} 秒仍未就绪' -f $Seconds) }
}

function Wait-NginxReady {
    param([int]$Seconds)

    $deadline = (Get-Date).AddSeconds($Seconds)
    $lastError = ''
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $publicUrl -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            if ([int]$response.StatusCode -eq 200) {
                $health = Get-HealthSnapshot -Url $publicHealthUrl -TimeoutSec 3
                if ($health.HttpOk -and $health.Code -eq 0 -and $health.Status -eq 'UP' -and $health.Db -eq 'UP') {
                    return [pscustomobject]@{ Ok = $true; Health = $health; Error = '' }
                }
                $lastError = if ($health.Error) { $health.Error } else { ('8088 健康响应异常：code={0}, status={1}, db={2}' -f $health.Code, $health.Status, $health.Db) }
            } else {
                $lastError = ('GET / 返回 HTTP {0}' -f $response.StatusCode)
            }
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    }
    return [pscustomobject]@{ Ok = $false; Health = $null; Error = $lastError }
}

function Stop-OwnedProcess {
    param([int]$ProcessId, [string]$Label)

    if ($ProcessId -le 0) { return }
    try {
        $details = Get-ProcessDetails -ProcessId $ProcessId
        if (-not $details.Exists) { return }
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
        Write-Log ('已清理本次启动的{0}（PID {1}）' -f $Label, $ProcessId) 'WARN'
    } catch {
    }
}

try {
    Write-Section 'find-it-campus Windows 启动'
    Write-Log ('部署包目录：' + $pkgRoot)

    # ---------- 1. 配置与文件检查 ----------
    $configPath = Join-Path $pkgRoot 'config\deploy.env.ps1'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw ('缺少 config\deploy.env.ps1。请先运行 scripts\install.ps1 完成安装。')
    }

    . $configPath

    foreach ($name in @('DeployDbHost', 'DeployDbPort', 'DeployDbName', 'DeployDbUser', 'DeployMysqlBin', 'DeployJavaBin', 'DeployEngine')) {
        if (-not (Get-Variable -Name $name -Scope Local -ErrorAction SilentlyContinue)) {
            throw ('config\deploy.env.ps1 缺少变量 {0}，请重新运行 install.ps1。' -f $name)
        }
    }

    if (-not $env:FINDIT_DB_URL -or $env:FINDIT_DB_URL -match '__') {
        throw 'config\deploy.env.ps1 仍是模板占位内容，请重新运行 install.ps1。'
    }
    if ($env:FINDIT_MATCH_ENGINE -notin @('local', 'external')) {
        throw ('FINDIT_MATCH_ENGINE 非法：{0}' -f $env:FINDIT_MATCH_ENGINE)
    }

    $javaExe = $null
    if ($JavaBin) {
        $javaExe = Resolve-JavaExe -JavaBin $JavaBin
    } elseif ($DeployJavaBin -and (Test-Path -LiteralPath $DeployJavaBin -PathType Leaf)) {
        $javaExe = (Resolve-Path -LiteralPath $DeployJavaBin).Path
    } else {
        $javaExe = Resolve-JavaExe -JavaBin ''
    }
    if (-not $javaExe) {
        throw '未找到 java.exe：请安装 JDK 17 并设置 JAVA_HOME/PATH，或用 -JavaBin 指定。'
    }
    $javaInfo = Get-JavaVersionInfo -JavaExe $javaExe
    Write-Log ('JDK 17：' + $javaInfo.Path) 'OK'

    $jarPath = Join-Path $pkgRoot 'app\find-it-campus-0.0.1-SNAPSHOT.jar'
    $indexPath = Join-Path $pkgRoot 'web\dist\index.html'
    $nginxExe = Join-Path $pkgRoot 'nginx\nginx.exe'
    $nginxPrefix = Join-Path $pkgRoot 'nginx'
    $nginxConfig = Join-Path $nginxPrefix 'conf\nginx.conf'

    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw ('缺少后端 jar：' + $jarPath) }
    if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) { throw ('缺少前端构建产物：' + $indexPath) }
    if (-not (Test-Path -LiteralPath $nginxExe -PathType Leaf)) { throw ('缺少 Nginx：' + $nginxExe) }
    if (-not (Test-Path -LiteralPath $nginxConfig -PathType Leaf)) { throw ('缺少 Nginx 配置：' + $nginxConfig) }

    $logsDir = Join-Path $pkgRoot 'logs'
    $runtimeDir = Join-Path $pkgRoot 'runtime'
    New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

    $backendPidFile = Join-Path $runtimeDir 'backend.pid'
    $nginxPidFile = Join-Path $runtimeDir 'nginx.pid'
    $backendLog = Join-Path $logsDir 'backend.log'
    $backendErrLog = Join-Path $logsDir 'backend-error.log'
    $nginxErrorLog = Join-Path $logsDir 'nginx-error.log'

    Write-Log ('后端 jar：' + $jarPath)
    Write-Log ('前端目录：' + (Split-Path -Parent $indexPath))
    Write-Log ('Nginx：' + $nginxExe)
    Write-Log ('数据库：{0}:{1}/{2}，引擎：{3}' -f $DeployDbHost, $DeployDbPort, $DeployDbName, $DeployEngine)

    # ---------- 2. 后端：已运行则复用 ----------
    $backendAlreadyRunning = $false
    $backendInfo = Get-PidFileProcess -PidFile $backendPidFile -ExpectedExePath $javaExe -CommandLineNeedle $jarPath
    if ($backendInfo.Alive -and $backendInfo.Matches) {
        $backendAlreadyRunning = $true
        $backendPid = $backendInfo.Pid
        Write-Log ('后端已在运行（PID {0}），不重复拉起。' -f $backendPid) 'OK'
    } elseif ($backendInfo.PidFileExists) {
        if ($backendInfo.Alive -and -not $backendInfo.Matches) {
            Write-Log ('发现 backend.pid 指向非本包进程：{0}；将移除陈旧 PID 文件后继续。' -f $backendInfo.Reason) 'WARN'
        } else {
            Write-Log ('清理陈旧 backend.pid：' + $backendInfo.Reason) 'WARN'
        }
        Remove-Item -LiteralPath $backendPidFile -Force -ErrorAction SilentlyContinue
    }

    $backendPortOwners = @(Get-PortOwners -Port 18080)
    if ($backendPortOwners.Count -gt 0) {
        $owner = $backendPortOwners[0]
        if ($backendAlreadyRunning -and $owner.ProcessId -eq $backendPid) {
            Write-Log ('18080 已由本包后端（PID {0}）监听。' -f $backendPid) 'OK'
        } else {
            $ownerText = 'PID {0} / {1} / {2}' -f $owner.ProcessId, $owner.Name, $owner.Path
            throw ('端口 18080 已被其他进程占用：{0}。本脚本不会抢占或结束该进程；请先停止它，或用 status.ps1 查看。' -f $ownerText)
        }
    }

    if (-not $backendAlreadyRunning) {
        Write-Section '启动后端（127.0.0.1:18080）'
        if (Test-Path -LiteralPath $backendLog) {
            Move-Item -LiteralPath $backendLog -Destination ($backendLog + '.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.old') -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $backendErrLog) {
            Move-Item -LiteralPath $backendErrLog -Destination ($backendErrLog + '.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.old') -Force -ErrorAction SilentlyContinue
        }

        $javaArguments = @(
            '-jar',
            ('"{0}"' -f $jarPath),
            '--server.address=127.0.0.1',
            '--server.port=18080'
        )
        $backendProcess = Start-Process -FilePath $javaExe -ArgumentList $javaArguments `
            -WorkingDirectory $pkgRoot -RedirectStandardOutput $backendLog -RedirectStandardError $backendErrLog `
            -WindowStyle Hidden -PassThru
        $backendPid = [int]$backendProcess.Id
        $startedBackend = $true
        [System.IO.File]::WriteAllText($backendPidFile, [string]$backendPid, (New-Object System.Text.UTF8Encoding($false)))
        Write-Log ('已拉起后端：PID {0}，日志：{1}' -f $backendPid, $backendLog)

        $health = Wait-BackendHealthy -Seconds $TimeoutSec -Process $backendProcess
        if (-not $health.Ok) {
            Write-Log ('后端未就绪：' + $health.Reason) 'ERROR'
            foreach ($line in (Get-FileTail -Path $backendErrLog -Lines 20)) { Write-Log ('stderr | ' + $line) 'ERROR' }
            foreach ($line in (Get-FileTail -Path $backendLog -Lines 20)) { Write-Log ('stdout | ' + $line) 'ERROR' }
            throw ('后端健康检查未通过（{0}）。请检查 JDK17、数据库连接和 logs\backend-error.log。' -f $health.Reason)
        }
        Write-Log ('后端健康：code=0, status=UP, db=UP, engine={0}' -f $health.Snapshot.Engine) 'OK'
    }

    # ---------- 3. Nginx：已运行则复用 ----------
    $nginxAlreadyRunning = $false
    $nginxInfo = Get-PidFileProcess -PidFile $nginxPidFile -ExpectedExePath $nginxExe -CommandLineNeedle $nginxExe
    if ($nginxInfo.Alive -and $nginxInfo.Matches) {
        $nginxAlreadyRunning = $true
        Write-Log ('Nginx 已在运行（PID {0}），不重复拉起。' -f $nginxInfo.Pid) 'OK'
    } elseif ($nginxInfo.PidFileExists) {
        Write-Log ('清理陈旧/不匹配的 nginx.pid：' + $nginxInfo.Reason) 'WARN'
        Remove-Item -LiteralPath $nginxPidFile -Force -ErrorAction SilentlyContinue
    }

    $nginxPortOwners = @(Get-PortOwners -Port 8088)
    if ($nginxPortOwners.Count -gt 0) {
        $owner = $nginxPortOwners[0]
        $ownerIsOurNginx = $false
        if ($nginxAlreadyRunning) {
            if ($owner.ProcessId -eq $nginxInfo.Pid) {
                $ownerIsOurNginx = $true
            } else {
                # Windows 上监听 8088 的通常是 nginx worker，而 nginx.pid 记的是 master。
                $ownerDetails = Get-ProcessDetails -ProcessId $owner.ProcessId
                if (Test-ProcessMatches -Details $ownerDetails -ExpectedExePath $nginxExe -CommandLineNeedle '') {
                    $ownerIsOurNginx = $true
                }
            }
        }
        if ($ownerIsOurNginx) {
            Write-Log ('8088 已由本包 Nginx（监听 PID {0}，master {1}）占用。' -f $owner.ProcessId, $nginxInfo.Pid) 'OK'
        } else {
            $ownerText = 'PID {0} / {1} / {2}' -f $owner.ProcessId, $owner.Name, $owner.Path
            throw ('端口 8088 已被其他进程占用：{0}。本脚本不会抢占或结束该进程。' -f $ownerText)
        }
    }

    $nginxPid = if ($nginxAlreadyRunning) { $nginxInfo.Pid } else { 0 }
    if (-not $nginxAlreadyRunning) {
        Write-Section '启动 Nginx（8088）'
        $nginxArguments = @(
            '-p', ('"{0}/"' -f $nginxPrefix),
            '-c', 'conf/nginx.conf'
        )
        $nginxStdoutLog = Join-Path $logsDir 'nginx-stdout.log'
        $nginxStderrLog = Join-Path $logsDir 'nginx-stderr.log'
        $nginxProcess = Start-Process -FilePath $nginxExe -ArgumentList $nginxArguments `
            -WorkingDirectory $nginxPrefix -WindowStyle Hidden `
            -RedirectStandardOutput $nginxStdoutLog -RedirectStandardError $nginxStderrLog -PassThru

        # nginx.conf 里 pid 指向 runtime\nginx.pid；等待 nginx 自己写。
        $pidDeadline = (Get-Date).AddSeconds(10)
        while ((Get-Date) -lt $pidDeadline -and -not (Test-Path -LiteralPath $nginxPidFile)) {
            Start-Sleep -Milliseconds 300
        }
        if (Test-Path -LiteralPath $nginxPidFile) {
            $pidText = (Get-Content -LiteralPath $nginxPidFile -Raw -ErrorAction SilentlyContinue)
            if ($pidText -and $pidText.Trim() -match '^\d+$') { $nginxPid = [int]$pidText.Trim() }
        }
        if ($nginxPid -le 0 -and -not $nginxProcess.HasExited) {
            # 兜底：极少数情况下 pid 文件未落盘，但 master 进程还在。
            $nginxPid = [int]$nginxProcess.Id
            [System.IO.File]::WriteAllText($nginxPidFile, [string]$nginxPid, (New-Object System.Text.UTF8Encoding($false)))
        }
        $startedNginx = $true

        $nginxReady = Wait-NginxReady -Seconds 30
        if (-not $nginxReady.Ok) {
            Write-Log ('Nginx 未就绪：' + $nginxReady.Error) 'ERROR'
            foreach ($line in (Get-FileTail -Path $nginxErrorLog -Lines 20)) { Write-Log ('nginx-error | ' + $line) 'ERROR' }
            foreach ($line in (Get-FileTail -Path $nginxStderrLog -Lines 20)) { Write-Log ('nginx-stderr | ' + $line) 'ERROR' }
            if (Test-Path -LiteralPath $nginxPidFile) {
                $failedPidText = (Get-Content -LiteralPath $nginxPidFile -Raw -ErrorAction SilentlyContinue)
                if ($failedPidText -and $failedPidText.Trim() -match '^\d+$') {
                    Stop-OwnedProcess -ProcessId ([int]$failedPidText.Trim()) -Label ' Nginx'
                    Remove-Item -LiteralPath $nginxPidFile -Force -ErrorAction SilentlyContinue
                }
            }
            throw ('Nginx 未能提供 8088 服务（{0}）。请检查 logs\nginx-error.log 和 nginx\conf\nginx.conf。' -f $nginxReady.Error)
        }
        Write-Log ('Nginx 已就绪：{0} → code=0, status=UP, db=UP' -f $publicUrl) 'OK'
    } else {
        $existingHealth = Get-HealthSnapshot -Url $publicHealthUrl -TimeoutSec 5
        if (-not ($existingHealth.HttpOk -and $existingHealth.Code -eq 0 -and $existingHealth.Status -eq 'UP' -and $existingHealth.Db -eq 'UP')) {
            throw ('Nginx 进程在运行，但 8088 健康检查未通过：code={0}, status={1}, db={2}, error={3}' -f $existingHealth.Code, $existingHealth.Status, $existingHealth.Db, $existingHealth.Error)
        }
    }

    # ---------- 4. 汇总 ----------
    $finalHealth = Get-HealthSnapshot -Url $publicHealthUrl -TimeoutSec 5
    if (-not ($finalHealth.HttpOk -and $finalHealth.Code -eq 0 -and $finalHealth.Status -eq 'UP' -and $finalHealth.Db -eq 'UP')) {
        throw ('启动后最终健康检查未通过：code={0}, status={1}, db={2}, error={3}' -f $finalHealth.Code, $finalHealth.Status, $finalHealth.Db, $finalHealth.Error)
    }

    Write-Section '启动完成'
    Write-Log ('后端：PID {0}，http://127.0.0.1:18080/api/health' -f $backendPid) 'OK'
    Write-Log ('Nginx：PID {0}，{1}' -f $nginxPid, $publicUrl) 'OK'
    Write-Log ('健康：code=0, status=UP, db=UP, engine={0}' -f $finalHealth.Engine) 'OK'
    Write-Log ('访问：' + $publicUrl)
    Write-Log ('启动日志：' + (Get-ScriptLogPath))
    exit 0
} catch {
    Write-Log ('启动失败：' + $_.Exception.Message) 'ERROR'
    if ($_.ScriptStackTrace) { Write-Log ('调用栈：' + $_.ScriptStackTrace) 'ERROR' }
    if ($startedBackend) {
        Stop-OwnedProcess -ProcessId $backendPid -Label '后端'
    }
    if ($startedNginx) {
        $failedPid = 0
        if (Test-Path -LiteralPath $nginxPidFile) {
            $failedPidText = (Get-Content -LiteralPath $nginxPidFile -Raw -ErrorAction SilentlyContinue)
            if ($failedPidText -and $failedPidText.Trim() -match '^\d+$') { $failedPid = [int]$failedPidText.Trim() }
        }
        Stop-OwnedProcess -ProcessId $failedPid -Label ' Nginx'
    }
    if ($script:LogFile) { Write-Log ('详细日志：' + $script:LogFile) 'ERROR' }
    exit 1
}
