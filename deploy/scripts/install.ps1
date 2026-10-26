# =====================================================================
# find-it-campus · Windows 部署安装脚本
#
# 作用：校验 JDK17 / MySQL8 客户端 → 建库（utf8mb4）→ 导入 schema/seed
#       → 由 config/deploy.env.example 生成 config/deploy.env.ps1。
# 边界：不注册 Windows 服务、不修改系统环境变量、不需要管理员权限。
#
# 示例（完全非交互，供自动验收；带 -ExecutionPolicy Bypass 是为了在默认
#       Restricted 的机器上也能直接跑，见 DEPLOY-WINDOWS.md §2.1）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1 `
#       -DbHost 127.0.0.1 -DbPort 3306 -DbName find_it_campus `
#       -DbUser root -DbPassword '你的密码' -Engine local `
#       -MysqlBin 'C:\mysql\bin\mysql.exe' -JavaBin 'C:\jdk17\bin\java.exe'
#
# 空密码自动化：追加 -EmptyDbPassword（不要同时传 -DbPassword）。
# =====================================================================

[CmdletBinding()]
param(
    [string]$DbHost = '',
    [int]$DbPort = 0,
    [string]$DbName = '',
    [string]$DbUser = '',
    [AllowEmptyString()][string]$DbPassword = '',
    [ValidateSet('local', 'external')][string]$Engine = '',
    [string]$MysqlBin = '',
    [string]$JavaBin = '',
    [switch]$EmptyDbPassword,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1')
Initialize-ConsoleEncoding
Set-ScriptLog 'install'

$pkgRoot = Get-PackageRoot

function Read-ValueOrDefault {
    param([string]$Prompt, [string]$Default)
    if ($Default) {
        $entered = Read-Host ('{0} [{1}]' -f $Prompt, $Default)
    } else {
        $entered = Read-Host $Prompt
    }
    if ([string]::IsNullOrWhiteSpace($entered)) { return $Default }
    return $entered.Trim()
}

try {
    Write-Section 'find-it-campus Windows 部署安装'
    Write-Log ('部署包目录：' + $pkgRoot)
    Write-Log ('安装日志：' + (Get-ScriptLogPath))

    # ---------- 1. 参数：齐全时绝不提示；缺省才交互 ----------
    $passwordBound = $PSBoundParameters.ContainsKey('DbPassword') -or $EmptyDbPassword
    $allBound = $PSBoundParameters.ContainsKey('DbHost') -and
                $PSBoundParameters.ContainsKey('DbPort') -and
                $PSBoundParameters.ContainsKey('DbName') -and
                $PSBoundParameters.ContainsKey('DbUser') -and
                $PSBoundParameters.ContainsKey('Engine') -and
                $passwordBound

    if ($EmptyDbPassword -and $PSBoundParameters.ContainsKey('DbPassword')) {
        throw '不能同时使用 -DbPassword 与 -EmptyDbPassword。'
    }

    if (-not $allBound) {
        Write-Section '交互式参数（缺少的参数才提示）'
        if (-not $PSBoundParameters.ContainsKey('DbHost')) {
            $DbHost = Read-ValueOrDefault 'MySQL 主机' '127.0.0.1'
        }
        if (-not $PSBoundParameters.ContainsKey('DbPort')) {
            $enteredPort = Read-ValueOrDefault 'MySQL 端口' '3306'
            if (-not [int]::TryParse($enteredPort, [ref]$DbPort)) {
                throw ('端口不是整数：{0}' -f $enteredPort)
            }
        }
        if (-not $PSBoundParameters.ContainsKey('DbName')) {
            $DbName = Read-ValueOrDefault '数据库名' 'find_it_campus'
        }
        if (-not $PSBoundParameters.ContainsKey('DbUser')) {
            $DbUser = Read-ValueOrDefault '数据库账号' 'root'
        }
        if (-not $PSBoundParameters.ContainsKey('Engine')) {
            $Engine = Read-ValueOrDefault '匹配引擎（local/external）' 'local'
        }
        if (-not $passwordBound) {
            $secure = Read-Host '数据库密码（直接回车表示空密码，输入不回显）' -AsSecureString
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try {
                $DbPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
            } finally {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
            }
        }
    }

    if (-not $DbHost) { $DbHost = '127.0.0.1' }
    if ($DbPort -eq 0) { $DbPort = 3306 }
    if (-not $DbName) { $DbName = 'find_it_campus' }
    if (-not $DbUser) { $DbUser = 'root' }
    if (-not $Engine) { $Engine = 'local' }

    if ($DbHost -notmatch '^[A-Za-z0-9_.:-]+$') { throw ('DbHost 含非法字符：{0}' -f $DbHost) }
    if ($DbPort -lt 1 -or $DbPort -gt 65535) { throw ('DbPort 超出范围：{0}' -f $DbPort) }
    if ($DbName -notmatch '^[A-Za-z0-9_]{1,64}$') { throw ('DbName 只允许字母/数字/下划线，且不超过 64 字符：{0}' -f $DbName) }
    if ($DbUser -notmatch '^[A-Za-z0-9_.@-]{1,64}$') { throw ('DbUser 含非法字符：{0}' -f $DbUser) }
    if ($DbPassword -match '[\r\n]') { throw '数据库密码不能包含换行符。' }
    if ($Engine -notin @('local', 'external')) { throw ('Engine 只能是 local 或 external：{0}' -f $Engine) }

    if ($Force) {
        Write-Log '已指定 -Force：如果目标库已存在，将先 DROP DATABASE 再重建，库内现有数据会全部丢失。' 'WARN'
    }

    Write-Log ('目标 MySQL：{0}:{1}，库：{2}，账号：{3}，引擎：{4}' -f $DbHost, $DbPort, $DbName, $DbUser, $Engine)

    # ---------- 2. JDK 17 ----------
    Write-Section '检查 JDK 17'
    $javaExe = Resolve-JavaExe -JavaBin $JavaBin
    if (-not $javaExe) {
        if (-not $allBound) {
            $javaExe = Resolve-ExecutablePath -PathValue (Read-ValueOrDefault 'JDK17 的 java.exe（或 bin 目录）' '') -ExeName 'java.exe' -Label 'Java'
        } else {
            throw '未找到 java.exe：请安装 JDK 17 并设置 JAVA_HOME/PATH，或用 -JavaBin 指定。'
        }
    }
    $javaInfo = Get-JavaVersionInfo -JavaExe $javaExe
    Write-Log ('JDK 17 可用：' + $javaExe) 'OK'
    Write-Log ($javaInfo.Version.Split("`n")[0].Trim())

    # ---------- 3. MySQL 8 客户端与连通性 ----------
    Write-Section '检查 MySQL 8 客户端与连通性'
    $mysqlExe = Resolve-MysqlExe -MysqlBin $MysqlBin
    if (-not $mysqlExe) {
        if (-not $allBound) {
            $mysqlExe = Resolve-ExecutablePath -PathValue (Read-ValueOrDefault 'mysql.exe（或 bin 目录）' '') -ExeName 'mysql.exe' -Label 'MySQL 客户端'
        } else {
            throw '未找到 mysql.exe：请安装 MySQL 8 客户端，或用 -MysqlBin 指定 mysql.exe 或 bin 目录。'
        }
    }
    Write-Log ('MySQL 客户端：' + $mysqlExe) 'OK'

    $optionFile = New-MysqlOptionFile -DbHost $DbHost -DbPort $DbPort -DbUser $DbUser -DbPassword $DbPassword
    try {
        $versionText = Invoke-MysqlScalar -MysqlExe $mysqlExe -OptionFile $optionFile -Query 'SELECT VERSION();'
        $serverVersion = ($versionText -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1).Trim()
        if ($serverVersion -notmatch '^8\.') {
            throw ('需要 MySQL 8.x，服务端返回版本：{0}' -f $serverVersion)
        }
        Write-Log ('MySQL 服务端版本：' + $serverVersion) 'OK'

        $existsText = Invoke-MysqlScalar -MysqlExe $mysqlExe -OptionFile $optionFile `
            -Query ("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '{0}';" -f $DbName)
        $dbExists = ([int](($existsText -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1).Trim())) -gt 0

        if ($dbExists -and -not $Force) {
            throw ("数据库 {0} 已存在。默认不覆盖现有数据；如确认要清空重建，请追加 -Force。" -f $DbName)
        }
        if ($dbExists -and $Force) {
            Write-Log ('-Force：删除已存在数据库 ' + $DbName) 'WARN'
            Invoke-MysqlScalar -MysqlExe $mysqlExe -OptionFile $optionFile `
                -Query ('DROP DATABASE `{0}`;' -f $DbName) | Out-Null
        }

        Invoke-MysqlScalar -MysqlExe $mysqlExe -OptionFile $optionFile `
            -Query ('CREATE DATABASE `{0}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;' -f $DbName) | Out-Null
        $charset = (Invoke-MysqlScalar -MysqlExe $mysqlExe -OptionFile $optionFile `
            -Query ("SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '{0}';" -f $DbName)).Trim()
        if ($charset -ne 'utf8mb4') {
            throw ('数据库 {0} 字符集为 {1}，不是 utf8mb4。' -f $DbName, $charset)
        }
        Write-Log ('数据库已创建（utf8mb4）：' + $DbName) 'OK'

        # ---------- 4. 导入 schema / seed ----------
        Write-Section '导入 schema.sql 与 seed.sql'
        $schemaPath = Join-Path $pkgRoot 'db\schema.sql'
        $seedPath = Join-Path $pkgRoot 'db\seed.sql'
        if (-not (Test-Path -LiteralPath $schemaPath)) { throw ('包内缺少 db\schema.sql：' + $schemaPath) }
        if (-not (Test-Path -LiteralPath $seedPath)) { throw ('包内缺少 db\seed.sql：' + $seedPath) }

        $runtimeDir = Join-Path $pkgRoot 'runtime'
        New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $schemaTmp = Join-Path $runtimeDir ('.install-{0}-schema.sql' -f $stamp)
        $seedTmp = Join-Path $runtimeDir ('.install-{0}-seed.sql' -f $stamp)

        # db/*.sql 是仓库内固定脚本，库名写死为 find_it_campus；这里只在临时副本中替换为目标库名，
        # 不改包内原始 db 文件，保证可重复安装到自定义库名。
        $schemaText = [System.IO.File]::ReadAllText($schemaPath, [System.Text.Encoding]::UTF8)
        $schemaText = $schemaText.Replace('`find_it_campus`', ('`' + $DbName + '`')).Replace('find_it_campus', $DbName)
        [System.IO.File]::WriteAllText($schemaTmp, $schemaText, (New-Object System.Text.UTF8Encoding($false)))

        $seedText = [System.IO.File]::ReadAllText($seedPath, [System.Text.Encoding]::UTF8)
        $seedText = $seedText.Replace('`find_it_campus`', ('`' + $DbName + '`')).Replace('find_it_campus', $DbName)
        [System.IO.File]::WriteAllText($seedTmp, $seedText, (New-Object System.Text.UTF8Encoding($false)))

        Write-Log ('执行 schema.sql → ' + $DbName)
        $schemaResult = Invoke-MysqlFile -MysqlExe $mysqlExe -OptionFile $optionFile -SqlFile $schemaTmp -Database $DbName
        if ($schemaResult.ExitCode -ne 0) {
            Write-Log ($schemaResult.StdErr.Trim()) 'ERROR'
            throw ('schema.sql 导入失败（退出码 {0}）。' -f $schemaResult.ExitCode)
        }

        Write-Log ('执行 seed.sql → ' + $DbName)
        $seedResult = Invoke-MysqlFile -MysqlExe $mysqlExe -OptionFile $optionFile -SqlFile $seedTmp -Database $DbName
        if ($seedResult.ExitCode -ne 0) {
            Write-Log ($seedResult.StdErr.Trim()) 'ERROR'
            throw ('seed.sql 导入失败（退出码 {0}）。' -f $seedResult.ExitCode)
        }

        $categoryCount = [int]((Invoke-MysqlScalar -MysqlExe $mysqlExe -OptionFile $optionFile -Database $DbName `
            -Query 'SELECT COUNT(*) FROM `t_category`;').Trim())
        $recordCount = [int]((Invoke-MysqlScalar -MysqlExe $mysqlExe -OptionFile $optionFile -Database $DbName `
            -Query 'SELECT COUNT(*) FROM `t_record`;').Trim())
        Write-Log ('导入校验：t_category={0} 行，t_record={1} 行' -f $categoryCount, $recordCount) 'OK'
        if ($categoryCount -lt 9) { throw ('t_category 只有 {0} 行，期望至少 9 行。' -f $categoryCount) }
        if ($recordCount -lt 24) { throw ('t_record 只有 {0} 行，期望至少 24 行。' -f $recordCount) }

        Remove-Item -LiteralPath $schemaTmp -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $seedTmp -Force -ErrorAction SilentlyContinue

        # ---------- 5. 生成 config/deploy.env.ps1 ----------
        Write-Section '生成部署连接配置'
        $templatePath = Join-Path $pkgRoot 'config\deploy.env.example'
        if (-not (Test-Path -LiteralPath $templatePath)) { throw ('包内缺少 config\deploy.env.example：' + $templatePath) }

        function ConvertTo-SingleQuotedValue {
            param([AllowEmptyString()][string]$Value)
            return $Value.Replace("'", "''")
        }

        $template = [System.IO.File]::ReadAllText($templatePath, [System.Text.Encoding]::UTF8)
        $replacements = [ordered]@{
            '__DB_HOST__'     = ConvertTo-SingleQuotedValue $DbHost
            '__DB_PORT__'     = [string]$DbPort
            '__DB_NAME__'     = ConvertTo-SingleQuotedValue $DbName
            '__DB_USER__'     = ConvertTo-SingleQuotedValue $DbUser
            '__DB_PASSWORD__' = ConvertTo-SingleQuotedValue $DbPassword
            '__MYSQL_BIN__'   = ConvertTo-SingleQuotedValue $mysqlExe
            '__JAVA_BIN__'    = ConvertTo-SingleQuotedValue $javaExe
            '__ENGINE__'      = ConvertTo-SingleQuotedValue $Engine
        }
        foreach ($key in $replacements.Keys) {
            $template = $template.Replace($key, [string]$replacements[$key])
        }
        if ($template -match '__DB_(HOST|PORT|NAME|USER|PASSWORD)__|__MYSQL_BIN__|__JAVA_BIN__|__ENGINE__') {
            throw 'deploy.env.example 中仍有未替换的占位符，请检查模板。'
        }

        $configPath = Join-Path $pkgRoot 'config\deploy.env.ps1'
        [System.IO.File]::WriteAllText($configPath, $template, (New-Object System.Text.UTF8Encoding($true)))

        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $aclOk = $false
        try {
            & icacls.exe $configPath /inheritance:r /grant:r ("{0}:(F)" -f $identity) 2>&1 | Out-Null
            $aclOk = ($LASTEXITCODE -eq 0)
        } catch {
        }
        $readable = $false
        try {
            [System.IO.File]::ReadAllText($configPath) | Out-Null
            $readable = $true
        } catch {
        }
        if (-not $readable) {
            # 绝不能因为“加固 ACL”把部署者自己锁在文件外；失败时恢复继承并放宽给当前身份。
            try {
                & icacls.exe $configPath /inheritance:e /grant:r ("{0}:(F)" -f $identity) 2>&1 | Out-Null
            } catch {
            }
            Write-Log 'ACL 加固后当前用户无法读取 deploy.env.ps1，已恢复继承权限。请自行确认该文件仅本机可读。' 'WARN'
        } elseif (-not $aclOk) {
            Write-Log '未能收窄 deploy.env.ps1 的 ACL（不影响运行，请自行确认文件权限）。' 'WARN'
        }
        Write-Log ('已生成（含真实密码，已被 .gitignore 忽略）：' + $configPath) 'OK'

        if ($Engine -eq 'external') {
            Write-Log 'Engine=external：还需要手工编辑 config\deploy.env.ps1 填写 FINDIT_EMBED_BASE_URL / FINDIT_EMBED_API_KEY，否则匹配接口会失败。' 'WARN'
        }

        Write-Section '安装完成'
        Write-Log ('数据库：{0}:{1}/{2}（utf8mb4）' -f $DbHost, $DbPort, $DbName) 'OK'
        Write-Log ('配置：' + $configPath) 'OK'
        Write-Log '下一步：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1  （启动后端 18080 与 Nginx 8088）'
        Write-Log '访问：http://127.0.0.1:8088/'
        Write-Log ('安装日志：' + (Get-ScriptLogPath))
        exit 0
    } finally {
        Remove-MysqlOptionFile -Path $optionFile
    }
} catch {
    Write-Log ('安装失败：' + $_.Exception.Message) 'ERROR'
    if ($_.ScriptStackTrace) {
        Write-Log ('调用栈：' + $_.ScriptStackTrace) 'ERROR'
    }
    if ($script:LogFile) {
        Write-Log ('详细日志：' + $script:LogFile) 'ERROR'
    }
    exit 1
}
