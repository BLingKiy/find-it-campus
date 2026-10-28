# =====================================================================
# find-it-campus · Windows 部署包构建脚本
#
# 用法（从仓库根目录或任意目录执行均可）：
#   .\deploy\build-package.ps1
#   .\deploy\build-package.ps1 -SkipBuild          # 复用已有 jar / web\dist
#   .\deploy\build-package.ps1 -MavenBin 'C:\maven\bin\mvn.cmd' -JavaHome 'C:\jdk17'
#
# 产物：deploy\dist\find-it-campus-windows-x64-<sha7>.zip 与同名 .sha256
# 构建机需要：JDK17、Maven（或 server\mvnw.cmd）、Node22/npm；目标机不需要。
# =====================================================================

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [string]$MavenBin = '',
    [string]$JavaHome = '',
    [string]$NpmBin = '',
    [string]$NginxZip = '',
    [switch]$KeepStaging
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$ProgressPreference = 'SilentlyContinue'

$deployRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $deployRoot
$packageName = 'find-it-campus-windows-x64'
$nginxVersion = '1.30.6'
$nginxSha256 = 'C6EE35EDB0A861063323F709192AE50D1EEFAF2EA7D00678BD2B22D8542DC61E'
$nginxUrl = 'https://nginx.org/download/nginx-1.30.6.zip'

$stagingRoot = Join-Path $deployRoot '.staging'
$stage = Join-Path $stagingRoot $packageName
$distDir = Join-Path $deployRoot 'dist'
$cacheDir = Join-Path $deployRoot '.cache'
$logDir = Join-Path $cacheDir 'logs'

New-Item -ItemType Directory -Force -Path $cacheDir, $logDir, $distDir | Out-Null
$buildLog = Join-Path $logDir ('build-package-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

function Write-BuildLog {
    param([string]$Message, [ValidateSet('INFO', 'OK', 'WARN', 'ERROR', 'STEP')][string]$Level = 'INFO')
    $line = '[{0}] [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    [Console]::Out.WriteLine($line)
    Add-Content -LiteralPath $buildLog -Value $line -Encoding UTF8
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$WorkingDirectory = ''
    )

    if ($WorkingDirectory) {
        Push-Location $WorkingDirectory
    }
    try {
        # Out-Host：让 Maven 输出照常显示，但不进入函数返回值；
        # 否则 $code 会变成“输出行 + 退出码”的数组，后面的 -ne 0 判断失效。
        & $FilePath @Arguments | Out-Host
        $exitCode = $LASTEXITCODE
        return $exitCode
    } finally {
        if ($WorkingDirectory) { Pop-Location }
    }
}

function Get-ToolPath {
    param([string]$Explicit, [string]$Name)

    if ($Explicit) {
        if (Test-Path -LiteralPath $Explicit -PathType Leaf) { return (Resolve-Path -LiteralPath $Explicit).Path }
        throw ("找不到 {0}：{1}" -f $Name, $Explicit)
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

function Get-GitInfo {
    $git = Get-Command 'git.exe' -ErrorAction SilentlyContinue
    if (-not $git) {
        return [pscustomobject]@{ Branch = 'unknown'; Sha = ('unknown-' + (Get-Date -Format 'yyyyMMddHHmmss')); Dirty = $true; DirtyFiles = @('git not available') }
    }
    $branch = (& $git.Source -C $repoRoot rev-parse --abbrev-ref HEAD 2>$null | Select-Object -First 1)
    $sha = (& $git.Source -C $repoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    $status = @(& $git.Source -C $repoRoot status --porcelain 2>$null)
    if (-not $branch) { $branch = 'unknown' }
    if (-not $sha) { $sha = 'unknown' }
    $dirtyFiles = @($status | Where-Object { $_ -and $_.Trim() })
    return [pscustomobject]@{ Branch = [string]$branch; Sha = [string]$sha; Dirty = ($dirtyFiles.Count -gt 0); DirtyFiles = $dirtyFiles }
}

function Invoke-Download {
    param([string]$Url, [string]$OutFile)

    if (Test-Path -LiteralPath $OutFile) { Remove-Item -LiteralPath $OutFile -Force }

    try {
        Write-BuildLog ('下载：' + $Url)
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -TimeoutSec 180
        if (Test-Path -LiteralPath $OutFile -PathType Leaf) { return }
    } catch {
        Write-BuildLog ('Invoke-WebRequest 失败：' + $_.Exception.Message) 'WARN'
        Remove-Item -LiteralPath $OutFile -Force -ErrorAction SilentlyContinue
    }

    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if ($curl) {
        try {
            & $curl.Source -fL --max-time 300 -o $OutFile $Url
            if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $OutFile -PathType Leaf)) { return }
        } catch {
            Write-BuildLog ('curl 失败：' + $_.Exception.Message) 'WARN'
        }
    }

    $node = Get-Command 'node.exe' -ErrorAction SilentlyContinue
    if ($node) {
        try {
            $helper = Join-Path $deployRoot 'tools\download.mjs'
            & $node.Source $helper $Url $OutFile
            if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $OutFile -PathType Leaf)) { return }
        } catch {
            Write-BuildLog ('Node 下载失败：' + $_.Exception.Message) 'WARN'
        }
    }

    throw ('无法下载 {0}。请手工下载后放到 {1}，或用 -NginxZip 指定本地文件。' -f $Url, (Join-Path $cacheDir 'nginx-1.30.6.zip'))
}

function Get-NginxPackage {
    param([string]$ExplicitZip)

    $zipPath = $ExplicitZip
    if (-not $zipPath) {
        $zipPath = Join-Path $cacheDir ('nginx-{0}.zip' -f $nginxVersion)
        if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) {
            Invoke-Download -Url $nginxUrl -OutFile $zipPath
        }
    }
    if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) {
        throw ('Nginx 压缩包不存在：' + $zipPath)
    }

    $actual = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
    if ($actual -ne $nginxSha256) {
        throw ('Nginx 压缩包 SHA256 不匹配。期望 {0}，实际 {1}。文件：{2}' -f $nginxSha256, $actual, $zipPath)
    }
    Write-BuildLog ('Nginx 包校验通过：{0}（{1}）' -f $zipPath, $actual) 'OK'

    $extractRoot = Join-Path $cacheDir ('nginx-extract-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
    try {
        # 不用 Expand-Archive：它在无控制台/受限环境下会因进度条读取控制台缓冲而报
        # "Win32 internal error Access is denied"。ZipFile 直接解压更稳。
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $extractRoot)
        $candidate = Join-Path $extractRoot ('nginx-{0}' -f $nginxVersion)
        if (-not (Test-Path -LiteralPath (Join-Path $candidate 'nginx.exe') -PathType Leaf)) {
            $exe = Get-ChildItem -LiteralPath $extractRoot -Recurse -Filter 'nginx.exe' -File | Select-Object -First 1
            if (-not $exe) { throw 'Nginx 压缩包内未找到 nginx.exe。' }
            $candidate = Split-Path -Parent $exe.FullName
        }
        return [pscustomobject]@{ ZipPath = $zipPath; Sha256 = $actual; Root = $candidate }
    } finally {
        # 调用方复制完再清理；这里不删，交给 Copy-Nginx 后的清理。
    }
}

function Copy-DirectoryContents {
    param([string]$Source, [string]$Destination)
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Copy-Item -Path (Join-Path $Source '*') -Destination $Destination -Recurse -Force
}

function Get-FileToolVersion {
    param([string]$FilePath, [string[]]$Arguments)
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& $FilePath @Arguments 2>&1 | Out-String).Trim()
        if (-not $output) { return 'unknown' }
        $firstLine = (($output -split "`r?`n") | Where-Object { $_.Trim() } | Select-Object -First 1).Trim()
        # 原生命令写 stderr 时 PowerShell 5.1 会加 "java.exe : " 之类前缀。
        return ($firstLine -replace '^[A-Za-z0-9_.-]+\.exe\s*:\s*', '')
    } catch {
        return ('error: ' + $_.Exception.Message)
    } finally {
        $ErrorActionPreference = $oldEap
    }
}

try {
    Write-BuildLog '==== find-it-campus Windows 部署包构建 ====' 'STEP'
    Write-BuildLog ('仓库根目录：' + $repoRoot)
    Write-BuildLog ('构建日志：' + $buildLog)

    $gitInfo = Get-GitInfo
    Write-BuildLog ('Git：{0} @ {1}，dirty={2}' -f $gitInfo.Branch, $gitInfo.Sha, $gitInfo.Dirty)

    # ---------- 1. 工具链 ----------
    Write-BuildLog '检查构建工具链' 'STEP'
    $javaExe = $null
    if ($JavaHome) {
        $javaExe = Join-Path $JavaHome 'bin\java.exe'
    } elseif ($env:JAVA_HOME) {
        $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else {
        $javaCmd = Get-Command 'java.exe' -ErrorAction SilentlyContinue
        if ($javaCmd) { $javaExe = $javaCmd.Source }
    }
    if (-not $javaExe -or -not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
        throw '未找到 JDK17。请安装 JDK17，或传入 -JavaHome（JDK 根目录）。'
    }
    $javaVersion = Get-FileToolVersion -FilePath $javaExe -Arguments @('-version')
    if ($javaVersion -notmatch 'version "(\d+)') {
        throw ('无法识别 Java 版本：' + $javaVersion)
    }
    if ([int]$matches[1] -ne 17) {
        throw ('构建需要 JDK 17，当前：{0}' -f $javaVersion)
    }
    Write-BuildLog ('JDK：' + $javaVersion) 'OK'

    $mavenExe = $null
    if ($MavenBin) {
        $mavenExe = Get-ToolPath -Explicit $MavenBin -Name 'Maven'
    } else {
        $mavenExe = Get-ToolPath -Explicit '' -Name 'mvn.cmd'
        if (-not $mavenExe) { $mavenExe = Get-ToolPath -Explicit '' -Name 'mvn' }
        if (-not $mavenExe) {
            $wrapper = Join-Path $repoRoot 'server\mvnw.cmd'
            if (Test-Path -LiteralPath $wrapper -PathType Leaf) { $mavenExe = $wrapper }
        }
    }
    if (-not $mavenExe) { throw '未找到 Maven。请安装 Maven，或传入 -MavenBin，或在 server\ 下保留 mvnw.cmd。' }
    # mvn.cmd 自身需要 JAVA_HOME 才能打印版本；探版本时也要先指向已校验的 JDK17，
    # 否则 BUILD-INFO 里会出现 "The JAVA_HOME environment variable is not defined correctly"。
    $jdkHome = Split-Path -Parent (Split-Path -Parent $javaExe)
    $savedJavaHomeForMavenProbe = $env:JAVA_HOME
    $env:JAVA_HOME = $jdkHome
    try {
        $mavenVersion = Get-FileToolVersion -FilePath $mavenExe -Arguments @('-v')
    } finally {
        if ($savedJavaHomeForMavenProbe) {
            $env:JAVA_HOME = $savedJavaHomeForMavenProbe
        } else {
            Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
        }
    }
    Write-BuildLog ('Maven：' + $mavenVersion) 'OK'

    $npmExe = $null
    if ($NpmBin) {
        $npmExe = Get-ToolPath -Explicit $NpmBin -Name 'npm'
    } else {
        $npmExe = Get-ToolPath -Explicit '' -Name 'npm.cmd'
        if (-not $npmExe) { $npmExe = Get-ToolPath -Explicit '' -Name 'npm' }
    }
    if (-not $SkipBuild -and -not $npmExe) { throw '未找到 npm。请安装 Node 22 / npm，或传入 -NpmBin。' }
    $nodeExe = Get-ToolPath -Explicit '' -Name 'node.exe'
    $nodeVersion = if ($nodeExe) { Get-FileToolVersion -FilePath $nodeExe -Arguments @('-v') } else { 'unknown' }
    $npmVersion = if ($npmExe) { Get-FileToolVersion -FilePath $npmExe -Arguments @('-v') } else { 'unknown' }
    Write-BuildLog ('Node：{0}；npm：{1}' -f $nodeVersion, $npmVersion) 'OK'

    # ---------- 2. 编译后端 / 前端 ----------
    $jarPath = Join-Path $repoRoot 'server\target\find-it-campus-0.0.1-SNAPSHOT.jar'
    $webDist = Join-Path $repoRoot 'web\dist'

    if (-not $SkipBuild) {
        Write-BuildLog '构建后端：mvn -DskipTests package' 'STEP'
        $oldJavaHome = $env:JAVA_HOME
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExe)
        try {
            $code = Invoke-Native -FilePath $mavenExe -Arguments @('-f', (Join-Path $repoRoot 'server\pom.xml'), '-DskipTests', 'package')
            if ($code -ne 0) { throw ('后端构建失败，退出码 {0}' -f $code) }
        } finally {
            if ($oldJavaHome) { $env:JAVA_HOME = $oldJavaHome } else { Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue }
        }
        if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw ('后端构建后仍找不到 jar：' + $jarPath) }

        Write-BuildLog '构建前端：npm ci && npm run build' 'STEP'
        $webDir = Join-Path $repoRoot 'web'
        Push-Location $webDir
        try {
            & $npmExe 'ci'
            if ($LASTEXITCODE -ne 0) { throw ('npm ci 失败，退出码 {0}' -f $LASTEXITCODE) }
            & $npmExe 'run' 'build'
            if ($LASTEXITCODE -ne 0) { throw ('npm run build 失败，退出码 {0}' -f $LASTEXITCODE) }
        } finally {
            Pop-Location
        }
        if (-not (Test-Path -LiteralPath (Join-Path $webDist 'index.html') -PathType Leaf)) {
            throw ('前端构建后仍找不到 web\dist\index.html')
        }
    } else {
        Write-BuildLog '已指定 -SkipBuild：复用现有 jar / web\dist。' 'WARN'
        if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw ('缺少 jar：' + $jarPath) }
        if (-not (Test-Path -LiteralPath (Join-Path $webDist 'index.html') -PathType Leaf)) { throw ('缺少 web\dist\index.html') }
    }

    $nginxPackage = Get-NginxPackage -ExplicitZip $NginxZip
    $nginxExe = Join-Path $nginxPackage.Root 'nginx.exe'
    $nginxVersionText = Get-FileToolVersion -FilePath $nginxExe -Arguments @('-v')
    Write-BuildLog ('Nginx：{0}' -f $nginxVersionText) 'OK'

    # ---------- 3. 组装 staging ----------
    Write-BuildLog '组装包目录' 'STEP'
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null

    $appDir = Join-Path $stage 'app'
    $webDirOut = Join-Path $stage 'web\dist'
    $nginxDir = Join-Path $stage 'nginx'
    $dbDir = Join-Path $stage 'db'
    $configDir = Join-Path $stage 'config'
    $scriptsDir = Join-Path $stage 'scripts'
    $logsDir = Join-Path $stage 'logs'
    $runtimeDir = Join-Path $stage 'runtime'

    foreach ($dir in @($appDir, $webDirOut, $nginxDir, $dbDir, $configDir, $scriptsDir, $logsDir, $runtimeDir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }

    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $appDir 'find-it-campus-0.0.1-SNAPSHOT.jar') -Force
    Copy-DirectoryContents -Source $webDist -Destination $webDirOut
    Copy-Item -LiteralPath (Join-Path $repoRoot 'db\schema.sql') -Destination (Join-Path $dbDir 'schema.sql') -Force
    Copy-Item -LiteralPath (Join-Path $repoRoot 'db\seed.sql') -Destination (Join-Path $dbDir 'seed.sql') -Force
    Copy-Item -LiteralPath (Join-Path $deployRoot 'config\deploy.env.example') -Destination (Join-Path $configDir 'deploy.env.example') -Force
    Copy-Item -Path (Join-Path $deployRoot 'scripts\*.ps1') -Destination $scriptsDir -Force
    Copy-Item -LiteralPath (Join-Path $deployRoot 'DEPLOY-WINDOWS.md') -Destination (Join-Path $stage 'DEPLOY-WINDOWS.md') -Force

    # Nginx：只带运行所需内容 + 许可证/README，避免整包 docs/contrib 膨胀。
    Copy-Item -LiteralPath $nginxExe -Destination (Join-Path $nginxDir 'nginx.exe') -Force
    Copy-DirectoryContents -Source (Join-Path $nginxPackage.Root 'conf') -Destination (Join-Path $nginxDir 'conf')
    Copy-Item -LiteralPath (Join-Path $deployRoot 'nginx\nginx.conf') -Destination (Join-Path $nginxDir 'conf\nginx.conf') -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $nginxDir 'logs'), (Join-Path $nginxDir 'temp') | Out-Null
    $nginxDocs = Join-Path $nginxPackage.Root 'docs'
    if (Test-Path -LiteralPath (Join-Path $nginxDocs 'LICENSE') -PathType Leaf) {
        New-Item -ItemType Directory -Force -Path (Join-Path $nginxDir 'docs') | Out-Null
        Copy-Item -LiteralPath (Join-Path $nginxDocs 'LICENSE') -Destination (Join-Path $nginxDir 'docs\LICENSE') -Force
        if (Test-Path -LiteralPath (Join-Path $nginxDocs 'README.md') -PathType Leaf) {
            Copy-Item -LiteralPath (Join-Path $nginxDocs 'README.md') -Destination (Join-Path $nginxDir 'docs\README.md') -Force
        }
    }

    # 清理 Nginx 解压临时目录
    try { Remove-Item -LiteralPath $nginxPackage.Root -Recurse -Force -ErrorAction SilentlyContinue } catch {}

    # 空目录是包布局的一部分；用 .keep 占位，确保 Compress/解压工具不会丢掉。
    foreach ($dir in @($logsDir, $runtimeDir, (Join-Path $nginxDir 'logs'), (Join-Path $nginxDir 'temp'))) {
        if (@(Get-ChildItem -LiteralPath $dir -Force).Count -eq 0) {
            [System.IO.File]::WriteAllText((Join-Path $dir '.keep'), '', (New-Object System.Text.UTF8Encoding($false)))
        }
    }

    # ---------- 4. BUILD-INFO ----------
    $jarHash = (Get-FileHash -LiteralPath (Join-Path $appDir 'find-it-campus-0.0.1-SNAPSHOT.jar') -Algorithm SHA256).Hash
    $indexHash = (Get-FileHash -LiteralPath (Join-Path $webDirOut 'index.html') -Algorithm SHA256).Hash
    $buildTime = Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'
    $dirtyText = if ($gitInfo.Dirty) { 'true' } else { 'false' }
    $dirtyList = if ($gitInfo.Dirty) { ($gitInfo.DirtyFiles | Select-Object -First 20) -join '; ' } else { '' }

    $buildInfo = @(
        'find-it-campus Windows x64 deployment package',
        ('Package name: ' + $packageName),
        'Package layout version: 7.4',
        ('Build time: ' + $buildTime),
        ('Git branch: ' + $gitInfo.Branch),
        ('Git commit: ' + $gitInfo.Sha),
        ('Git dirty: ' + $dirtyText),
        $(if ($dirtyList) { 'Git dirty files (first 20): ' + $dirtyList } else { 'Git dirty files: (none)' }),
        ('Build host OS: ' + [System.Environment]::OSVersion.VersionString),
        ('JDK: ' + $javaVersion),
        ('Maven: ' + $mavenVersion),
        ('Node: ' + $nodeVersion),
        ('npm: ' + $npmVersion),
        ('Nginx: ' + $nginxVersion + ' (Windows stable, official nginx.org build)'),
        ('Nginx download: ' + $nginxUrl),
        ('Nginx zip SHA256: ' + $nginxPackage.Sha256),
        ('Nginx binary: ' + $nginxVersionText),
        'Backend jar: app/find-it-campus-0.0.1-SNAPSHOT.jar',
        ('Backend jar SHA256: ' + $jarHash),
        'Web entry: web/dist/index.html',
        ('Web index SHA256: ' + $indexHash),
        'Runtime ports: 8088 (nginx, public) -> 127.0.0.1:18080 (backend)',
        ('Build command: deploy\build-package.ps1' + $(if ($SkipBuild) { ' -SkipBuild' } else { '' }))
    )
    [System.IO.File]::WriteAllLines((Join-Path $stage 'BUILD-INFO.txt'), $buildInfo, (New-Object System.Text.UTF8Encoding($false)))
    Write-BuildLog '已生成 BUILD-INFO.txt' 'OK'

    # ---------- 5. SHA256SUMS ----------
    $sumsPath = Join-Path $stage 'SHA256SUMS.txt'
    $files = @(Get-ChildItem -LiteralPath $stage -Recurse -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } | Sort-Object FullName)
    $sumLines = @()
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($stage.Length + 1) -replace '\\', '/'
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        $sumLines += ('{0} *{1}' -f $hash, $relative)
    }
    [System.IO.File]::WriteAllLines($sumsPath, $sumLines, (New-Object System.Text.UTF8Encoding($false)))
    Write-BuildLog ('已生成 SHA256SUMS.txt（{0} 个文件）' -f $sumLines.Count) 'OK'

    # ---------- 6. 打 zip ----------
    Write-BuildLog '生成 zip' 'STEP'
    $sha7 = $gitInfo.Sha.Substring(0, [Math]::Min(7, $gitInfo.Sha.Length))
    $zipName = '{0}-{1}.zip' -f $packageName, $sha7
    $zipPath = Join-Path $distDir $zipName
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
    try {
        $archive.CreateEntry($packageName + '/') | Out-Null
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($stage.Length + 1) -replace '\\', '/'
            $entryName = $packageName + '/' + $relative
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $file.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
        $shaEntry = $archive.CreateEntry($packageName + '/SHA256SUMS.txt')
        $shaBytes = [System.IO.File]::ReadAllBytes($sumsPath)
        $shaStream = $shaEntry.Open()
        try { $shaStream.Write($shaBytes, 0, $shaBytes.Length) } finally { $shaStream.Dispose() }
    } finally {
        $archive.Dispose()
    }

    $zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
    $zipSize = (Get-Item -LiteralPath $zipPath).Length
    $shaFile = $zipPath + '.sha256'
    [System.IO.File]::WriteAllText($shaFile, ('{0} *{1}' -f $zipHash, $zipName), (New-Object System.Text.UTF8Encoding($false)))

    if (-not $KeepStaging) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-BuildLog '==== 构建完成 ====' 'STEP'
    Write-BuildLog ('ZIP：' + $zipPath) 'OK'
    Write-BuildLog ('SHA256：' + $zipHash) 'OK'
    Write-BuildLog ('大小：{0} 字节（{1}）' -f $zipSize, ('{0:N2} MB' -f ($zipSize / 1MB))) 'OK'
    Write-BuildLog ('SHA256 文件：' + $shaFile)
    Write-BuildLog ('构建日志：' + $buildLog)
    [Console]::Out.WriteLine('')
    [Console]::Out.WriteLine('PACKAGE_PATH=' + $zipPath)
    [Console]::Out.WriteLine('PACKAGE_SHA256=' + $zipHash)
    [Console]::Out.WriteLine('PACKAGE_SIZE_BYTES=' + $zipSize)
    exit 0
} catch {
    Write-BuildLog ('构建失败：' + $_.Exception.Message) 'ERROR'
    if ($_.ScriptStackTrace) { Write-BuildLog ('调用栈：' + $_.ScriptStackTrace) 'ERROR' }
    Write-BuildLog ('构建日志：' + $buildLog) 'ERROR'
    exit 1
}
