# =====================================================================
# find-it-campus · Windows 部署脚本公共函数
# 被 install.ps1 / start.ps1 / stop.ps1 / status.ps1 点源引用。
# 目标运行时：Windows PowerShell 5.1（Windows 10/11 自带），无需管理员权限。
# =====================================================================

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
# Invoke-WebRequest / Invoke-RestMethod 的进度条会在无控制台或受限环境下读取
# 控制台缓冲并抛 "Win32 internal error Access is denied"；部署脚本不需要进度条。
$ProgressPreference = 'SilentlyContinue'

# 包根目录 = scripts 目录的上一级。函数内统一用 $script:PackageRoot，
# 避免依赖调用方当前目录或被点源后的 $PSScriptRoot 语义。
$script:PackageRoot = Split-Path -Parent $PSScriptRoot
$script:LogFile = $null
$script:ScriptName = 'deploy'

function Initialize-ConsoleEncoding {
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [Console]::OutputEncoding = $utf8
        $global:OutputEncoding = $utf8
    } catch {
        # 输出被重定向到文件/管道时 Set 可能失败，不影响脚本逻辑。
    }
}

function Get-PackageRoot {
    return $script:PackageRoot
}

function Get-Timestamp {
    return (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
}

function Set-ScriptLog {
    param([Parameter(Mandatory = $true)][string]$Name)

    $script:ScriptName = $Name
    $logDir = Join-Path $script:PackageRoot 'logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $script:LogFile = Join-Path $logDir ('{0}-{1}.log' -f $Name, (Get-Date -Format 'yyyyMMdd-HHmmss'))
    # 先创建文件，确保失败时状态脚本也能看到本次日志路径。
    [System.IO.File]::WriteAllText($script:LogFile, '', (New-Object System.Text.UTF8Encoding($false)))
}

function Get-ScriptLogPath {
    return $script:LogFile
}

function Write-Log {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Message,
        [ValidateSet('INFO', 'OK', 'WARN', 'ERROR', 'STEP')][string]$Level = 'INFO'
    )

    $line = '[{0}] [{1}] {2}' -f (Get-Timestamp), $Level, $Message

    # 用 Console.Out 而不是 Write-Host：Write-Host 走宿主/information stream，
    # 当调用方把 stdout 重定向到文件时抓不到；Console.Out 始终进 stdout，
    # 既方便人工看，也方便 status/start 的输出被验收脚本原样收集。
    try {
        [Console]::Out.WriteLine($line)
    } catch {
        Write-Host $line
    }

    if ($script:LogFile) {
        try {
            Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8
        } catch {
            # 日志写失败不应掩盖主流程；下一次调用若恢复则继续写。
        }
    }
}

function Write-Section {
    param([Parameter(Mandatory = $true)][string]$Title)
    Write-Log ''
    Write-Log ('==== ' + $Title + ' ====') 'STEP'
}

function Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [int]$ExitCode = 1
    )

    Write-Log $Message 'ERROR'
    if ($script:LogFile) {
        Write-Log ('详细日志：' + $script:LogFile) 'ERROR'
    }
    exit $ExitCode
}

function Resolve-ExecutablePath {
    param(
        [string]$PathValue,
        [Parameter(Mandatory = $true)][string]$ExeName,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($PathValue -and $PathValue.Trim().Length -gt 0) {
        $candidate = $PathValue.Trim().Trim('"')
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            $candidate = Join-Path $candidate $ExeName
        }
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        throw ("找不到{0}：{1}（应为可执行文件或包含 {2} 的目录）" -f $Label, $PathValue, $ExeName)
    }

    $command = Get-Command $ExeName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return $null
}

function Resolve-JavaExe {
    param([string]$JavaBin)

    if ($JavaBin -and $JavaBin.Trim().Length -gt 0) {
        return (Resolve-ExecutablePath -PathValue $JavaBin -ExeName 'java.exe' -Label 'Java')
    }

    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $command = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return $null
}

function Get-JavaVersionInfo {
    param([Parameter(Mandatory = $true)][string]$JavaExe)

    # java -version 固定写 stderr；在 ErrorActionPreference=Stop 下直接 2>&1 会被
    # PowerShell 5.1 当成 NativeCommandError。这里临时放宽，仅收集文本。
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = (& $JavaExe -version 2>&1 | Out-String).Trim()
        $javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldEap
    }
    if ($javaExitCode -ne 0) {
        throw ("无法执行 java -version（退出码 {0}）：{1}" -f $javaExitCode, $raw)
    }

    $major = -1
    if ($raw -match 'version "(\d+)(?:\.(\d+))?') {
        $major = [int]$matches[1]
    } elseif ($raw -match 'openjdk version "(\d+)(?:\.(\d+))?') {
        $major = [int]$matches[1]
    } else {
        throw ("无法从 java -version 输出识别主版本：{0}" -f $raw)
    }

    if ($major -ne 17) {
        throw ("需要 JDK 17，当前 java 主版本为 {0}。请安装 JDK 17，或用 -JavaBin 指向 JDK17 的 java.exe。输出：{1}" -f $major, $raw)
    }

    $versionLine = (($raw -split "`r?`n") | Where-Object { $_.Trim() } | Select-Object -First 1).Trim()
    $versionLine = $versionLine -replace '^java\.exe\s*:\s*', ''

    return [pscustomobject]@{
        Path    = $JavaExe
        Major   = $major
        Version = $versionLine
    }
}

function Resolve-MysqlExe {
    param([string]$MysqlBin)

    if ($MysqlBin -and $MysqlBin.Trim().Length -gt 0) {
        return (Resolve-ExecutablePath -PathValue $MysqlBin -ExeName 'mysql.exe' -Label 'MySQL 客户端')
    }

    $command = Get-Command 'mysql.exe' -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return $null
}

function New-MysqlOptionFile {
    param(
        [Parameter(Mandatory = $true)][string]$DbHost,
        [Parameter(Mandatory = $true)][int]$DbPort,
        [Parameter(Mandatory = $true)][string]$DbUser,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$DbPassword
    )

    $runtimeDir = Join-Path $script:PackageRoot 'runtime'
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
    $cnf = Join-Path $runtimeDir ('.mysql-' + [guid]::NewGuid().ToString('N') + '.cnf')

    # MySQL option file: 双引号值内，反斜杠与双引号需要转义；换行会破坏文件格式，调用方已拒绝。
    $escaped = $DbPassword.Replace('\', '\\').Replace('"', '\"')
    $content = "[client]`r`nhost=$DbHost`r`nport=$DbPort`r`nuser=$DbUser`r`npassword=`"$escaped`"`r`nprotocol=TCP`r`ndefault-character-set=utf8mb4`r`n"
    [System.IO.File]::WriteAllText($cnf, $content, (New-Object System.Text.UTF8Encoding($false)))

    # 密码只落临时文件，生命周期仅限本次命令；不额外改 ACL。
    # 原因：受限/沙箱身份下 icacls 可能把当前进程也挡在文件外（MySQL 会报
    # "Failed to open required defaults file"）。长期保存的 deploy.env.ps1 才做 ACL 加固。
    return $cnf
}

function Remove-MysqlOptionFile {
    param([string]$Path)
    if ($Path -and (Test-Path -LiteralPath $Path)) {
        try { Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue } catch {}
    }
}

function Invoke-MysqlScalar {
    param(
        [Parameter(Mandatory = $true)][string]$MysqlExe,
        [Parameter(Mandatory = $true)][string]$OptionFile,
        [Parameter(Mandatory = $true)][string]$Query,
        [string]$Database
    )

    $args = @(
        ("--defaults-extra-file=`"{0}`"" -f $OptionFile),
        '--protocol=TCP',
        '--batch',
        '--skip-column-names',
        '--default-character-set=utf8mb4'
    )
    if ($Database) {
        $args += ("--database=`"{0}`"" -f $Database)
    }
    $args += @('-e', $Query)

    $output = (& $MysqlExe @args 2>&1 | Out-String).Trim()
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        throw ("MySQL 查询失败（退出码 {0}）：{1}" -f $code, $output)
    }
    return $output
}

function Invoke-MysqlFile {
    param(
        [Parameter(Mandatory = $true)][string]$MysqlExe,
        [Parameter(Mandatory = $true)][string]$OptionFile,
        [Parameter(Mandatory = $true)][string]$SqlFile,
        [Parameter(Mandatory = $true)][string]$Database
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $MysqlExe
    $psi.Arguments = ("--defaults-extra-file=`"{0}`" --protocol=TCP --default-character-set=utf8mb4 --database=`"{1}`"" -f $OptionFile, $Database)
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    # 直接写原始 UTF-8 字节，避免 PowerShell 5.1 管道按 ANSI/GBK 转码导致中文数据损坏。
    $bytes = [System.IO.File]::ReadAllBytes($SqlFile)
    $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $process.StandardInput.BaseStream.Flush()
    $process.StandardInput.Close()

    $process.WaitForExit()
    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result
    $code = $process.ExitCode
    $process.Dispose()

    return [pscustomobject]@{
        ExitCode = $code
        StdOut   = $stdout
        StdErr   = $stderr
    }
}

function Test-TcpPort {
    param(
        [Parameter(Mandatory = $true)][string]$ComputerName,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMs = 2000
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($ComputerName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs)) {
            return $false
        }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-PortOwners {
    param([Parameter(Mandatory = $true)][int]$Port)

    $owners = @()
    $seen = @{}

    try {
        $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop
        foreach ($connection in $connections) {
            $procId = [int]$connection.OwningProcess
            if ($seen.ContainsKey($procId)) { continue }
            $seen[$procId] = $true
            $name = '?'
            $path = ''
            try {
                $proc = Get-Process -Id $procId -ErrorAction Stop
                $name = $proc.ProcessName
                try { $path = $proc.Path } catch {}
            } catch {}
            $owners += [pscustomobject]@{
                Port      = $Port
                ProcessId = $procId
                Name      = $name
                Path      = $path
            }
        }
    } catch {
    }

    if ($owners.Count -eq 0) {
        try {
            $lines = & netstat.exe -ano -p TCP 2>$null
            foreach ($line in $lines) {
                if ($line -notmatch '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$') { continue }
                if ([int]$matches[1] -ne $Port) { continue }
                $procId = [int]$matches[2]
                if ($seen.ContainsKey($procId)) { continue }
                $seen[$procId] = $true
                $name = '?'
                $path = ''
                try {
                    $proc = Get-Process -Id $procId -ErrorAction Stop
                    $name = $proc.ProcessName
                    try { $path = $proc.Path } catch {}
                } catch {}
                $owners += [pscustomobject]@{
                    Port      = $Port
                    ProcessId = $procId
                    Name      = $name
                    Path      = $path
                }
            }
        } catch {
        }
    }

    return @($owners)
}

function Wait-PortFree {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSec = 15
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((@(Get-PortOwners -Port $Port)).Count -eq 0) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return ((@(Get-PortOwners -Port $Port)).Count -eq 0)
}

function Get-ProcessDetails {
    param([Parameter(Mandatory = $true)][int]$ProcessId)

    $result = [pscustomobject]@{
        Exists         = $false
        ProcessId      = $ProcessId
        Name           = ''
        CommandLine    = ''
        ExecutablePath = ''
    }

    try {
        $cim = Get-CimInstance Win32_Process -Filter ("ProcessId={0}" -f $ProcessId) -ErrorAction Stop
        if ($cim) {
            $result.Exists = $true
            $result.Name = [string]$cim.Name
            $result.CommandLine = [string]$cim.CommandLine
            $result.ExecutablePath = [string]$cim.ExecutablePath
        }
    } catch {
    }

    if (-not $result.Exists) {
        try {
            $proc = Get-Process -Id $ProcessId -ErrorAction Stop
            $result.Exists = $true
            $result.Name = $proc.ProcessName
            try { $result.ExecutablePath = $proc.Path } catch {}
        } catch {
        }
    }

    return $result
}

function Normalize-PathText {
    param([string]$Value)
    if (-not $Value) { return '' }
    return ($Value -replace '/', '\').ToLowerInvariant()
}

function Test-ProcessMatches {
    param(
        [Parameter(Mandatory = $true)]$Details,
        [AllowEmptyString()][string]$ExpectedExePath,
        [AllowEmptyString()][string]$CommandLineNeedle
    )

    if (-not $Details.Exists) { return $false }

    if ($ExpectedExePath) {
        if (-not $Details.ExecutablePath) { return $false }
        if ((Normalize-PathText $Details.ExecutablePath) -ne (Normalize-PathText $ExpectedExePath)) {
            return $false
        }
    }

    if ($CommandLineNeedle) {
        if (-not $Details.CommandLine) { return $false }
        $haystack = Normalize-PathText $Details.CommandLine
        $needle = Normalize-PathText $CommandLineNeedle
        if (-not $haystack.Contains($needle)) { return $false }
    }

    return $true
}

function Get-PidFileProcess {
    param(
        [Parameter(Mandatory = $true)][string]$PidFile,
        [AllowEmptyString()][string]$ExpectedExePath,
        [AllowEmptyString()][string]$CommandLineNeedle
    )

    $info = [pscustomobject]@{
        PidFileExists = $false
        Pid           = 0
        Alive         = $false
        Matches       = $false
        Details       = $null
        Reason        = ''
    }

    if (-not (Test-Path -LiteralPath $PidFile -PathType Leaf)) {
        $info.Reason = 'PID 文件不存在'
        return $info
    }

    $info.PidFileExists = $true
    $raw = (Get-Content -LiteralPath $PidFile -Raw -ErrorAction SilentlyContinue)
    if (-not $raw) { $raw = '' }
    $raw = $raw.Trim()

    if ($raw -notmatch '^\d+$') {
        $info.Reason = ('PID 文件内容非法：{0}' -f $raw)
        return $info
    }

    $procId = [int]$raw
    $info.Pid = $procId
    $details = Get-ProcessDetails -ProcessId $procId
    $info.Details = $details

    if (-not $details.Exists) {
        $info.Reason = ('PID {0} 对应进程不存在（陈旧 PID 文件）' -f $procId)
        return $info
    }

    $info.Alive = $true
    if (Test-ProcessMatches -Details $details -ExpectedExePath $ExpectedExePath -CommandLineNeedle $CommandLineNeedle) {
        $info.Matches = $true
    } else {
        $info.Reason = ('PID {0} 存在但命令行/路径与部署包不匹配，拒绝操作' -f $procId)
    }

    return $info
}

function Find-PackageProcess {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ExpectedExePath,
        [AllowEmptyString()][string]$CommandLineNeedle
    )

    try {
        $candidates = Get-CimInstance Win32_Process -ErrorAction Stop
    } catch {
        return @()
    }

    $matches = @()
    foreach ($candidate in $candidates) {
        $details = [pscustomobject]@{
            Exists         = $true
            ProcessId      = [int]$candidate.ProcessId
            Name           = [string]$candidate.Name
            CommandLine    = [string]$candidate.CommandLine
            ExecutablePath = [string]$candidate.ExecutablePath
        }
        if (Test-ProcessMatches -Details $details -ExpectedExePath $ExpectedExePath -CommandLineNeedle $CommandLineNeedle) {
            $matches += $details
        }
    }
    return @($matches)
}

function Get-HealthSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSec = 5
    )

    $result = [pscustomobject]@{
        HttpOk     = $false
        StatusCode = 0
        Code       = $null
        Status     = ''
        Db         = ''
        Engine     = ''
        Error      = ''
    }

    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec -ErrorAction Stop
        $result.HttpOk = $true
        $result.StatusCode = [int]$response.StatusCode
        try {
            $json = $response.Content | ConvertFrom-Json
            $result.Code = $json.code
            if ($json.data) {
                $result.Status = [string]$json.data.status
                $result.Db = [string]$json.data.db
                if ($json.data.PSObject.Properties.Name -contains 'engine') {
                    $result.Engine = [string]$json.data.engine
                }
            }
        } catch {
            $result.Error = ('响应不是合法 JSON：{0}' -f $_.Exception.Message)
        }
    } catch {
        $result.Error = $_.Exception.Message
        try {
            if ($_.Exception.Response) {
                $result.StatusCode = [int]$_.Exception.Response.StatusCode
            }
        } catch {}
    }

    return $result
}

function Get-FileTail {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Lines = 15
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    try {
        return @(Get-Content -LiteralPath $Path -Tail $Lines -ErrorAction Stop)
    } catch {
        return @()
    }
}

function Format-FileSize {
    param([long]$Bytes)
    if ($Bytes -ge 1GB) { return ('{0:N2} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N2} MB' -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ('{0:N2} KB' -f ($Bytes / 1KB)) }
    return ('{0} B' -f $Bytes)
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}
