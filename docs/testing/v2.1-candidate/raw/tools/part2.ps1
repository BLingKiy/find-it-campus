# M6 part 2: Face C — degraded fallback, manual degraded rescan recovery,
# MATCHED/unlock preservation, and degraded-scan config rollback switch.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root = 'C:\Project\hifox-workspaces\panshishi'
$pkg = Join-Path $root 'm6-deploy\find-it-campus-windows-x64'
$tmp = Join-Path $root 'm6-tmp'
$outDir = Join-Path $root 'm6-evidence-part2'
$mysqlBin = 'C:\Project\tools\mysql\mysql-8.0.29-winx64\bin'
$mysql = Join-Path $mysqlBin 'mysql.exe'
$mysqladmin = Join-Path $mysqlBin 'mysqladmin.exe'
$mysqld = Join-Path $mysqlBin 'mysqld.exe'
$dataDir = Join-Path $root 'mysql-b1-data'
$db = 'findit_m6'
$nodePath = Join-Path $root 'work-42\find-it-campus\qa\node_modules'
$evidence = Join-Path $tmp 'part2-evidence.txt'
$keyFile = Join-Path $env:USERPROFILE '.hifox-secrets\deepseek_api_key.txt'
$deepseekKey = if (Test-Path $keyFile) { (Get-Content $keyFile -Raw).Trim() } else { '' }
$opsToken = 'm6-ops-token'

New-Item -ItemType Directory -Force -Path $tmp, $outDir | Out-Null
$lines = New-Object System.Collections.Generic.List[string]
$checks = New-Object System.Collections.Generic.List[object]
function Say([string]$s) { $lines.Add($s); [Console]::Out.WriteLine($s) }
function Section([string]$s) { Say ''; Say ('===== ' + $s + ' =====') }
function Save { [IO.File]::WriteAllLines($evidence, $lines, (New-Object Text.UTF8Encoding($false))) }
function Check([string]$name, [bool]$ok, [string]$detail) {
    $checks.Add([pscustomobject]@{ name = $name; ok = $ok; detail = $detail })
    Say ("CHECK {0} = {1} :: {2}" -f $name, $(if ($ok) { 'PASS' } else { 'FAIL' }), $detail)
}
function Q([string]$sql) {
    $out = & $mysql -u root -h 127.0.0.1 -P 3306 --default-character-set=utf8mb4 -N -B $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "mysql failed: $sql`n$out" }
    return (($out | Out-String).Trim())
}
function Start-MySql {
    if (Get-Process mysqld -ErrorAction SilentlyContinue) { return $false }
    Start-Process -FilePath $mysqld -ArgumentList "--datadir=$dataDir", '--port=3306', '--bind-address=127.0.0.1', '--console' `
        -RedirectStandardOutput (Join-Path $tmp 'mysql.out.log') -RedirectStandardError (Join-Path $tmp 'mysql.err.log') | Out-Null
    for ($i = 0; $i -lt 60; $i++) {
        & $mysqladmin --host=127.0.0.1 --port=3306 --user=root ping *> $null
        if ($LASTEXITCODE -eq 0) { return $true }
        Start-Sleep -Milliseconds 500
    }
    throw 'mysqld not ready'
}
function Stop-App {
    Push-Location $pkg
    try {
        & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop.ps1 2>&1 | ForEach-Object { Say ([string]$_) }
        Say ("STOP_EXIT=$LASTEXITCODE")
    } finally { Pop-Location }
}
function Start-App([hashtable]$envs) {
    foreach ($k in @('FINDIT_REVIEW_MODE', 'DEEPSEEK_API_KEY', 'FINDIT_LLM_BASE_URL', 'SPRING_APPLICATION_JSON', 'OPS_SCAN_TOKEN')) {
        Remove-Item ("Env:" + $k) -ErrorAction SilentlyContinue
    }
    if ($envs) { foreach ($k in $envs.Keys) { Set-Item ("Env:" + $k) $envs[$k] } }
    Stop-App
    Push-Location $pkg
    try {
        & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1 2>&1 | ForEach-Object { Say ([string]$_) }
        $ec = $LASTEXITCODE
        Say ("START_EXIT=$ec")
        if ($ec -ne 0) { throw 'start failed' }
    } finally { Pop-Location }
}
function Run-Node([string[]]$nodeArgs) {
    $env:NODE_PATH = $nodePath
    $out = @(& node @nodeArgs 2>&1 | ForEach-Object { [string]$_ })
    $ec = $LASTEXITCODE
    $out | ForEach-Object { Say $_ }
    Say ("NODE_EXIT=$ec")
    return [pscustomobject]@{ exit = $ec; text = ($out -join "`n") }
}
function Truncate-Business {
    Say (Q "SET FOREIGN_KEY_CHECKS=0; TRUNCATE t_access_log; TRUNCATE t_notification; TRUNCATE t_pair_score; TRUNCATE t_match_result; TRUNCATE t_review_audit; TRUNCATE t_record; SET FOREIGN_KEY_CHECKS=1; SELECT 'truncated';")
    Say ('records=' + (Q 'SELECT COUNT(*) FROM t_record;'))
}
function Screenshot([string]$student, [string]$pages, [string]$sub) {
    $dir = Join-Path $outDir $sub
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $env:M6_BASE = 'http://127.0.0.1:8088'
    $env:M6_SHOTS = $dir
    $env:M6_PAGES = $pages
    $env:M6_MODE = 'login'
    $env:M6_STUDENT = $student
    return (Run-Node @((Join-Path $tmp 'shots.cjs')))
}
function PairRow([long]$pairId) {
    return (Q ("SELECT CONCAT_WS('|',score,engine,IFNULL(degraded_reason,'NULL'),status,IFNULL(lost_confirmed_at,'NULL'),IFNULL(found_confirmed_at,'NULL'),IFNULL(lost_unlocked_at,'NULL'),IFNULL(found_unlocked_at,'NULL'),IFNULL(lost_unlock_note,'NULL'),IFNULL(found_unlock_note,'NULL'),updated_at) FROM t_pair_score WHERE id={0};" -f $pairId))
}
function RecordPairState([long]$id) {
    return (Q ("SELECT CONCAT_WS('|',pair_state,IFNULL(pair_scored_at,'NULL'),status) FROM t_record WHERE id={0};" -f $id))
}

$startedMysql = $false
$exit = 0
try {
    Section '0. env'
    Say ('pkg=' + $pkg)
    Say ('deepseek key=' + [bool]$deepseekKey + ' len=' + $deepseekKey.Length)
    Say ('ops token=' + $opsToken)

    Section '1. MySQL + app (review off, no key)'
    $startedMysql = Start-MySql
    Start-App @{ FINDIT_REVIEW_MODE = 'off'; OPS_SCAN_TOKEN = $opsToken }
    Truncate-Business

    Section '2. publish P01 pair -> degraded LOCAL_KEYWORD'
    $deg = Run-Node @((Join-Path $tmp 'm6-pairs.cjs'), 'degrade', 'P01')
    Check 'faceC.degrade.publish' ($deg.exit -eq 0) 'pair scored while no key'
    $degj = $deg.text | ConvertFrom-Json
    $lostId = [long]$degj.lostId
    $foundId = [long]$degj.foundId
    $pairId = [long]$degj.card.pairId
    Say ("lostId={0} foundId={1} pairId={2}" -f $lostId, $foundId, $pairId)
    $before = PairRow $pairId
    Say ('pair before=' + $before)
    $bp = $before -split '\|'
    Check 'faceC.degrade.engineLocal' ($bp[1] -eq 'LOCAL_KEYWORD') ('engine=' + $bp[1])
    Check 'faceC.degrade.reasonEmptyKey' ($bp[2] -eq 'EMPTY_KEY') ('degraded_reason=' + $bp[2])
    $lostState = RecordPairState $lostId
    $foundState = RecordPairState $foundId
    Say ('record state lost=' + $lostState + ' found=' + $foundState)
    Check 'faceC.degrade.recordState' ($lostState -match '^DEGRADED' -or $foundState -match '^DEGRADED') ('lost=' + $lostState + ' found=' + $foundState)

    Section '3. UI degraded banner'
    $shot = Screenshot 'demo02' ('/records/' + $lostId) 'shots-degraded'
    Check 'faceC.degrade.screenshot' ($shot.exit -eq 0) ('/records/' + $lostId)
    $shotJson = Get-Content (Join-Path $outDir 'shots-degraded\shots.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Check 'faceC.degrade.banner' ($shotJson[0].hasDegradedBanner -eq $true -and $shotJson[0].text -match '已降级') 'banner text 已降级 + 当前为关键词匹配'

    Section '4. restart with real key, confirm + unlock both sides'
    Start-App @{ FINDIT_REVIEW_MODE = 'off'; DEEPSEEK_API_KEY = $deepseekKey; OPS_SCAN_TOKEN = $opsToken }
    $env:M6_PAIR_ID = [string]$pairId
    $env:M6_LOST_ID = [string]$lostId
    $confirm = Run-Node @((Join-Path $tmp 'm6-pairs.cjs'), 'confirm')
    Check 'faceC.confirm.api' ($confirm.exit -eq 0) 'CONFIRM + contact-unlock from both sides'
    $pre = PairRow $pairId
    Say ('pair pre-recovery=' + $pre)
    $prep = $pre -split '\|'
    Check 'faceC.pre.MATCHED' ($prep[3] -eq 'MATCHED') ('status=' + $prep[3])
    Check 'faceC.pre.unlocked' ($prep[6] -ne 'NULL' -and $prep[7] -ne 'NULL') ('lost=' + $prep[6] + ' found=' + $prep[7])

    Section '5. age cooldown and trigger manual degraded scan'
    Say (Q ("UPDATE t_pair_score SET updated_at = DATE_SUB(NOW(), INTERVAL 61 MINUTE) WHERE id={0}; UPDATE t_record SET pair_state='DEGRADED', pair_scored_at=DATE_SUB(NOW(), INTERVAL 61 MINUTE) WHERE id IN ({1},{2}); SELECT 'aged';" -f $pairId, $lostId, $foundId))
    $hdr = Join-Path $tmp 'scan-headers.txt'
    $bodyFile = Join-Path $tmp 'scan-degraded.json'
    $code = (& curl.exe -s -D $hdr -o $bodyFile -w '%{http_code}' -X POST -H ("X-Ops-Token: " + $opsToken) 'http://127.0.0.1:8088/api/ops/pair-scan?scope=degraded' | Out-String).Trim()
    Say ('manual scan HTTP=' + $code)
    Say (Get-Content $bodyFile -Raw -Encoding UTF8)
    $scanJson = Get-Content $bodyFile -Raw -Encoding UTF8 | ConvertFrom-Json
    Say ('manual scan data=' + ($scanJson.data | ConvertTo-Json -Compress))
    Check 'faceC.rescan.http200' ($code -eq '200') ('HTTP=' + $code)
    Check 'faceC.rescan.scanned' (($scanJson.data.scanned -ge 1) -and -not $scanJson.data.skipReason) ('scanned=' + $scanJson.data.scanned + ' skipReason=' + $scanJson.data.skipReason)

    Start-Sleep -Seconds 4
    $after = PairRow $pairId
    Say ('pair after rescan=' + $after)
    $ap = $after -split '\|'
    Check 'faceC.rescan.engineDeepseek' ($ap[1] -eq 'DEEPSEEK') ('engine=' + $ap[1])
    Check 'faceC.rescan.reasonCleared' ($ap[2] -eq 'NULL') ('degraded_reason=' + $ap[2])
    Check 'faceC.rescan.scoreUpgraded' (([double]$ap[0]) -ge 0.80) ('score=' + $ap[0])
    Check 'faceC.rescan.statusPreserved' ($ap[3] -eq 'MATCHED') ('status=' + $ap[3])
    Check 'faceC.rescan.confirmPreserved' ($ap[4] -eq $prep[4] -and $ap[5] -eq $prep[5]) ('confirmed=' + $ap[4] + '/' + $ap[5])
    Check 'faceC.rescan.unlockPreserved' ($ap[6] -eq $prep[6] -and $ap[7] -eq $prep[7]) ('unlocked=' + $ap[6] + '/' + $ap[7])
    Check 'faceC.rescan.notesPreserved' ($ap[8] -eq $prep[8] -and $ap[9] -eq $prep[9]) ('notes=' + $ap[8] + '/' + $ap[9])
    Check 'faceC.rescan.recordReady' ((RecordPairState $lostId) -match '^READY') ('record=' + (RecordPairState $lostId))

    Section '6. config rollback: degraded-scan-enabled=false + 10s cron'
    Say (Q ("UPDATE t_pair_score SET engine='LOCAL_KEYWORD', degraded_reason='EMPTY_KEY', score=0.73, latency_ms=NULL, prompt_tokens=NULL, completion_tokens=NULL, updated_at=DATE_SUB(NOW(), INTERVAL 61 MINUTE) WHERE id={0}; UPDATE t_record SET pair_state='DEGRADED', pair_scored_at=DATE_SUB(NOW(), INTERVAL 61 MINUTE) WHERE id IN ({1},{2}); SELECT 'reset-to-degraded';" -f $pairId, $lostId, $foundId))
    $jsonOff = '{"findit":{"pair":{"score":{"degraded-scan-enabled":false,"degraded-scan-cron":"*/10 * * * * *"}}}}'
    Start-App @{ FINDIT_REVIEW_MODE = 'off'; DEEPSEEK_API_KEY = $deepseekKey; OPS_SCAN_TOKEN = $opsToken; SPRING_APPLICATION_JSON = $jsonOff }
    $logPath = Join-Path $pkg 'logs\backend.log'
    $marker = (Get-Date).AddSeconds(-2)
    Start-Sleep -Seconds 25
    $schedOff = @(Select-String -Path $logPath -Pattern 'trigger=degraded-scheduled' -ErrorAction SilentlyContinue).Count
    $rowOff = PairRow $pairId
    Say ('disabled after 25s: scheduledLogs=' + $schedOff + ' pair=' + $rowOff)
    $offp = $rowOff -split '\|'
    Check 'faceC.rollback.disabledNoScheduledScan' ($schedOff -eq 0 -and $offp[1] -eq 'LOCAL_KEYWORD') ('scheduledLogs=' + $schedOff + ' engine=' + $offp[1])

    Section '7. config re-enable + 10s cron -> scheduled scan upgrades'
    $jsonOn = '{"findit":{"pair":{"score":{"degraded-scan-enabled":true,"degraded-scan-cron":"*/10 * * * * *"}}}}'
    Start-App @{ FINDIT_REVIEW_MODE = 'off'; DEEPSEEK_API_KEY = $deepseekKey; OPS_SCAN_TOKEN = $opsToken; SPRING_APPLICATION_JSON = $jsonOn }
    Start-Sleep -Seconds 25
    $schedOn = @(Select-String -Path $logPath -Pattern 'trigger=degraded-scheduled' -ErrorAction SilentlyContinue).Count
    $upgraded = @(Select-String -Path $logPath -Pattern 'pair score upgraded|pair scan done scope=DEGRADED' -ErrorAction SilentlyContinue).Count
    $rowOn = PairRow $pairId
    Say ('enabled after 25s: scheduledLogs=' + $schedOn + ' upgradeLogs=' + $upgraded + ' pair=' + $rowOn)
    $onp = $rowOn -split '\|'
    Check 'faceC.rollback.enabledScheduledScan' ($schedOn -ge 1) ('scheduledLogs=' + $schedOn)
    Check 'faceC.rollback.enabledUpgrade' ($onp[1] -eq 'DEEPSEEK') ('engine=' + $onp[1])
    Check 'faceC.rollback.preservedAfterScheduled' ($onp[3] -eq 'MATCHED' -and $onp[4] -eq $prep[4] -and $onp[6] -eq $prep[6]) ('status=' + $onp[3] + ' confirmed=' + $onp[4] + ' unlocked=' + $onp[6])

    Section '8. stop + ports'
    Stop-App
    Start-Sleep -Seconds 1
    $port8088 = @(Get-NetTCPConnection -State Listen -LocalPort 8088 -ErrorAction SilentlyContinue).Count
    $port18080 = @(Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue).Count
    Check 'cleanup.portsReleased' ($port8088 -eq 0 -and $port18080 -eq 0) ("8088=$port8088 18080=$port18080")
} catch {
    Section 'FAILURE'
    Say $_.Exception.Message
    Say $_.ScriptStackTrace
    $exit = 1
} finally {
    Section 'cleanup'
    try { Stop-App } catch {}
    if ($startedMysql) { & $mysqladmin --host=127.0.0.1 --port=3306 --user=root shutdown *> $null; Say 'mysqld shutdown requested' }
    Section 'SUMMARY'
    foreach ($c in $checks) { Say ("{0} {1} :: {2}" -f $(if ($c.ok) { 'PASS' } else { 'FAIL' }), $c.name, $c.detail) }
    $failed = @($checks | Where-Object { -not $_.ok }).Count
    Say ("TOTAL={0} FAILED={1}" -f $checks.Count, $failed)
    if ($failed -gt 0) { $exit = 2 }
    Section 'RESULT'
    Say ($(if ($exit -eq 0) { 'PART2_OK' } else { 'PART2_FAIL' }))
    Save
    exit $exit
}
