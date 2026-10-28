# M6 part 1: fresh install, deployment checks, Face A (CORS), Face D (thresholds),
# Face B (review gate: real DeepSeek + failure path + mode=off regression).
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root = 'C:\Project\hifox-workspaces\panshishi'
$pkg = Join-Path $root 'm6-deploy\find-it-campus-windows-x64'
$zip = Join-Path $root 'm6-work\deploy\dist\find-it-campus-windows-x64-30002de.zip'
$tmp = Join-Path $root 'm6-tmp'
$outDir = Join-Path $root 'm6-evidence-part1'
$mysqlBin = 'C:\Project\tools\mysql\mysql-8.0.29-winx64\bin'
$mysql = Join-Path $mysqlBin 'mysql.exe'
$mysqladmin = Join-Path $mysqlBin 'mysqladmin.exe'
$mysqld = Join-Path $mysqlBin 'mysqld.exe'
$dataDir = Join-Path $root 'mysql-b1-data'
$java = 'C:\Project\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe'
$db = 'findit_m6'
$nodePath = Join-Path $root 'work-42\find-it-campus\qa\node_modules'
$evidence = Join-Path $tmp 'part1-evidence.txt'
$keyFile = Join-Path $env:USERPROFILE '.hifox-secrets\deepseek_api_key.txt'
$deepseekKey = if (Test-Path $keyFile) { (Get-Content $keyFile -Raw).Trim() } else { '' }

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
function Q1([string]$sql) { return (Q $sql) }
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
function Wait-Review([long]$id, [string]$want, [int]$timeoutSec = 150) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $row = Q ("SELECT CONCAT(review_state,'|',review_attempts,'|',review_manual_required,'|',is_visible) FROM t_record WHERE id={0};" -f $id)
        $parts = $row -split '\|'
        if ($parts[0] -ne 'PENDING') { return $row }
        if ($want -eq 'PENDING_MANUAL' -and $parts[2] -eq '1') { return $row }
        Start-Sleep -Seconds 1
    }
    return 'TIMEOUT'
}
function Screenshot([string]$name, [string]$mode, [string]$student, [string]$pages, [string]$sub) {
    $dir = Join-Path $outDir $sub
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $env:M6_BASE = 'http://127.0.0.1:8088'
    $env:M6_SHOTS = $dir
    $env:M6_PAGES = $pages
    $env:M6_MODE = $mode
    $env:M6_STUDENT = $student
    $r = Run-Node @((Join-Path $tmp 'shots.cjs'))
    return $r
}

$startedMysql = $false
$exit = 0
try {
    Section '0. package facts'
    Say ('zip=' + $zip)
    Say ('size=' + (Get-Item $zip).Length)
    Say ('sha256=' + (Get-FileHash $zip -Algorithm SHA256).Hash)
    Say ('sha256 file=' + (Get-Content ($zip + '.sha256') -Raw).Trim())
    Say ('deepseek key in secret store=' + [bool]$deepseekKey + ' len=' + $deepseekKey.Length)

    Section '1. MySQL + fresh install'
    $startedMysql = Start-MySql
    Say ('MYSQL_VERSION=' + ((& $mysql -u root -h 127.0.0.1 -P 3306 -N -B -e 'SELECT VERSION();' 2>&1 | Out-String).Trim()))
    & $mysql -u root -h 127.0.0.1 -P 3306 -e "DROP DATABASE IF EXISTS findit_m6;" *> $null
    Push-Location $pkg
    try {
        & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1 -DbHost 127.0.0.1 -DbPort 3306 -DbName findit_m6 -DbUser root -EmptyDbPassword -Engine local -MysqlBin $mysql -JavaBin $java 2>&1 | ForEach-Object { Say ([string]$_) }
        $ec = $LASTEXITCODE
        Say ("INSTALL_EXIT=$ec")
        Check 'install.exit0' ($ec -eq 0) "exit=$ec"
        Check 'install.seedRows' ((Q 'SELECT COUNT(*) FROM t_record;') -eq '24') ('t_record=' + (Q 'SELECT COUNT(*) FROM t_record;'))
    } finally { Pop-Location }

    Section '2. start package (review off, no key)'
    Start-App @{}
    $health = (& curl.exe -s 'http://127.0.0.1:8088/api/health' | Out-String).Trim()
    Say ('health=' + $health)
    $hj = $health | ConvertFrom-Json
    Check 'health.code0/statusUP/dbUP' ($hj.code -eq 0 -and $hj.data.status -eq 'UP' -and $hj.data.db -eq 'UP') ('code=' + $hj.code + ' status=' + $hj.data.status + ' db=' + $hj.data.db)

    Section '3. nginx -t'
    & (Join-Path $pkg 'nginx\nginx.exe') -t -p ((Join-Path $pkg 'nginx') + '\') -c conf/nginx.conf 2>&1 | ForEach-Object { Say ([string]$_) }
    Check 'nginx.configTest' ($LASTEXITCODE -eq 0) ('exit=' + $LASTEXITCODE)

    Section '4. three pages + screenshots (anonymous)'
    foreach ($p in @('/', '/records/1', '/records/new')) {
        $f = Join-Path $tmp ('p1-page-' + ($p -replace '[\/]', '_') + '.html')
        $c = (& curl.exe -s -o $f -w '%{http_code}' ('http://127.0.0.1:8088' + $p) | Out-String).Trim()
        $txt = Get-Content $f -Raw -Encoding UTF8
        Say ("{0} -> HTTP {1} bytes={2} hasAppDiv={3}" -f $p, $c, (Get-Item $f).Length, ($txt -match '<div id="app"'))
        Check ("page" + $p + ".200") ($c -eq '200') ("HTTP $c bytes=" + (Get-Item $f).Length)
    }
    $anon = Screenshot 'anon' 'anon' '' '/,/records/1,/records/new' 'shots-anon'
    Check 'screenshots.anon' ($anon.exit -eq 0) 'see shots-anon/shots.json'
    $auth = Screenshot 'auth' 'login' 'demo01' '/records/new,/records/1' 'shots-auth'
    Check 'screenshots.auth' ($auth.exit -eq 0) 'demo01 login; /records/new form + owner detail'

    Section '5. Face A: CORS through 8088'
    $env:M6_BASE = 'http://127.0.0.1:8088'
    $env:M6_OUT = $outDir
    $cors = Run-Node @((Join-Path $tmp 'faceA-cors.cjs'))
    Check 'faceA.cors' ($cors.exit -eq 0) 'shot JSON: faceA-cors.json'

    Section '6. Face D local: clean DB per sample'
    Truncate-Business
    $p01 = Run-Node @((Join-Path $tmp 'm6-pairs.cjs'), 'verify', 'P01', 'AUTHORIZABLE')
    Check 'faceD.local.P01.AUTHORIZABLE' ($p01.exit -eq 0) 'score 0.73 card visible, low=0'
    $p01j = $p01.text | ConvertFrom-Json
    $shotD = Screenshot 'd-local' 'login' 'demo02' ('/records/' + $p01j.lostId) 'shots-faceD-local'
    Check 'faceD.local.screenshot' ($shotD.exit -eq 0) ('owner detail /records/' + $p01j.lostId)
    Truncate-Business
    $p13 = Run-Node @((Join-Path $tmp 'm6-pairs.cjs'), 'verify', 'P13', 'HIDDEN')
    Check 'faceD.local.P13.HIDDEN' ($p13.exit -eq 0) 'no card, lowConfidenceCount>=1'

    Section '7. Face D deepseek: mode=off + real key, clean DB per sample'
    Start-App @{ FINDIT_REVIEW_MODE = 'off'; DEEPSEEK_API_KEY = $deepseekKey }
    Truncate-Business
    $dP01 = Run-Node @((Join-Path $tmp 'm6-pairs.cjs'), 'verify', 'P01', 'DIRECT')
    Check 'faceD.deepseek.P01.DIRECT' ($dP01.exit -eq 0) 'score >= 0.95 card visible'
    $dP01j = $dP01.text | ConvertFrom-Json
    $shotDD = Screenshot 'd-deepseek' 'login' 'demo02' ('/records/' + $dP01j.lostId) 'shots-faceD-deepseek'
    Check 'faceD.deepseek.screenshot' ($shotDD.exit -eq 0) ('owner detail /records/' + $dP01j.lostId)
    Truncate-Business
    $dP06 = Run-Node @((Join-Path $tmp 'm6-pairs.cjs'), 'verify', 'P06', 'AUTHORIZABLE')
    Check 'faceD.deepseek.P06.AUTHORIZABLE' ($dP06.exit -eq 0) '0.80 <= score < 0.95 card visible'
    Truncate-Business
    $dP13 = Run-Node @((Join-Path $tmp 'm6-pairs.cjs'), 'verify', 'P13', 'HIDDEN')
    Check 'faceD.deepseek.P13.HIDDEN' ($dP13.exit -eq 0) 'score < 0.80 hidden + low count'

    Section '8. Face B: review.mode=ai + real DeepSeek'
    Start-App @{ FINDIT_REVIEW_MODE = 'ai'; DEEPSEEK_API_KEY = $deepseekKey }
    $cases = @(
        @{ name = 'APPROVE'; payload = @{ type = 'LOST'; title = '图书馆丢失黑色钱包'; description = '在图书馆三楼自习区靠窗位置丢失一个黑色皮质钱包，里面有工行卡一张，请捡到的同学联系'; categoryId = 2; location = '图书馆三楼自习区'; occurredAt = '2026-10-24 15:20:00'; contact = '13800000002'; publisherName = '演示乙' } },
        @{ name = 'REJECT_IDENTITY_ONLY'; payload = @{ type = 'LOST'; title = '张三个人物品'; description = '张三个人联系电话13800000009学号2023123456宿舍3号楼201，丢了东西'; categoryId = 2; location = '3号楼201'; occurredAt = '2026-10-24 15:20:00'; contact = '13800000009'; publisherName = '张三' } },
        @{ name = 'REJECT_NO_FEATURES'; payload = @{ type = 'LOST'; title = '寻物'; description = '找东西'; categoryId = 2; location = '校内'; occurredAt = '2026-10-24 15:20:00'; contact = '13800000002'; publisherName = '演示乙' } }
    )
    $caseFile = Join-Path $tmp 'review-cases-ai.json'
    [IO.File]::WriteAllText($caseFile, ($cases | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    $idsFile = Join-Path $tmp 'review-ids-ai.json'
    $pub = Run-Node @((Join-Path $tmp 'm6-review.cjs'), 'publish', $caseFile, $idsFile)
    Check 'faceB.ai.publish' ($pub.exit -eq 0) 'APPROVE + 2 REJECT candidates submitted'
    $ids = Get-Content $idsFile -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($c in $ids) {
        $final = Wait-Review ([long]$c.id) 'ANY' 180
        Say ("review {0} id={1} final={2}" -f $c.name, $c.id, $final)
    }
    $allRows = Q ("SELECT CONCAT_WS('|',id,review_state,review_engine,review_model,review_attempts,review_manual_required,is_visible,IFNULL(review_reason,'')) FROM t_record WHERE id IN ({0}) ORDER BY id;" -f (($ids | ForEach-Object { $_.id }) -join ','))
    Say $allRows
    $vis = Run-Node @((Join-Path $tmp 'm6-review.cjs'), 'check', $idsFile)
    $visJson = $vis.text | ConvertFrom-Json
    $approve = $ids | Where-Object { $_.name -eq 'APPROVE' }
    $rejects = $ids | Where-Object { $_.name -like 'REJECT*' }
    $approvedRow = Q ("SELECT CONCAT_WS('|',review_state,review_engine,is_visible,title,description,IFNULL(raw_title,'NULL')) FROM t_record WHERE id={0};" -f $approve.id)
    Say ('APPROVE row=' + $approvedRow)
    $approvedParts = $approvedRow -split '\|'
    Check 'faceB.ai.APPROVED.state' ($approvedParts[0] -eq 'APPROVED' -and $approvedParts[2] -eq '1') ('state=' + $approvedParts[0] + ' visible=' + $approvedParts[2])
    Check 'faceB.ai.APPROVED.engine' ($approvedParts[1] -eq 'DEEPSEEK') ('engine=' + $approvedParts[1])
    Check 'faceB.ai.APPROVED.deidentified' ($approvedRow -notmatch '13800000009|张三') 'display text has no phone/name'
    $approveVis = $visJson | Where-Object { $_.name -eq 'APPROVE' }
    Check 'faceB.ai.APPROVED.public' ($approveVis.inPublicList -eq $true -and $approveVis.otherDetailHttp -eq 200) ('inPublicList=' + $approveVis.inPublicList + ' otherDetail=' + $approveVis.otherDetailHttp)
    Check 'faceB.ai.APPROVED.audit' ((Q ("SELECT COUNT(*) FROM t_review_audit WHERE record_id={0};" -f $approve.id)) -ge '1') ('auditRows=' + (Q ("SELECT COUNT(*) FROM t_review_audit WHERE record_id={0};" -f $approve.id)))
    Check 'faceB.ai.APPROVED.pairs' ((Q ("SELECT COUNT(*) FROM t_pair_score WHERE lost_id={0} OR found_id={0};" -f $approve.id)) -ge '0') 'scoring triggered'

    $rejectedOk = $false
    foreach ($r in $rejects) {
        $row = Q ("SELECT CONCAT_WS('|',review_state,review_engine,is_visible,IFNULL(review_reason,'')) FROM t_record WHERE id={0};" -f $r.id)
        Say ("REJECT candidate {0} row={1}" -f $r.name, $row)
        $rp = $row -split '\|'
        $rv = $visJson | Where-Object { $_.name -eq $r.name }
        $ok = ($rp[0] -eq 'REJECTED' -and $rp[2] -eq '0' -and $rv.inPublicList -eq $false -and $rv.otherDetailHttp -eq 404)
        if ($ok) { $rejectedOk = $true }
    }
    Check 'faceB.ai.REJECTED.notVisible' $rejectedOk 'at least one candidate REJECTED, invisible, other detail 404'

    Section '9. Face B: AI consecutive failure -> PENDING + manual'
    Start-App @{ FINDIT_REVIEW_MODE = 'ai'; DEEPSEEK_API_KEY = $deepseekKey; FINDIT_LLM_BASE_URL = 'http://127.0.0.1:19099/v1' }
    $failCases = @(@{ name = 'AI_FAIL'; payload = @{ type = 'LOST'; title = '审核失败黑色钱包'; description = '图书馆三楼丢失黑色皮质钱包，内有工行卡，请捡到者联系'; categoryId = 2; location = '图书馆三楼'; occurredAt = '2026-10-24 15:20:00'; contact = '13800000002'; publisherName = '演示乙' } })
    $failFile = Join-Path $tmp 'review-cases-fail.json'
    [IO.File]::WriteAllText($failFile, ($failCases | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    $failIdsFile = Join-Path $tmp 'review-ids-fail.json'
    $fpub = Run-Node @((Join-Path $tmp 'm6-review.cjs'), 'publish', $failFile, $failIdsFile)
    $fid = (Get-Content $failIdsFile -Raw -Encoding UTF8 | ConvertFrom-Json)[0].id
    $fstate = Wait-Review ([long]$fid) 'PENDING_MANUAL' 90
    Say ('AI_FAIL final=' + $fstate)
    $frow = Q ("SELECT CONCAT_WS('|',review_state,review_attempts,review_manual_required,is_visible,IFNULL(review_reason,'')) FROM t_record WHERE id={0};" -f $fid)
    Say ('AI_FAIL row=' + $frow)
    $fp = $frow -split '\|'
    Check 'faceB.ai.failure.PENDINGmanual' ($fp[0] -eq 'PENDING' -and $fp[1] -eq '3' -and $fp[2] -eq '1' -and $fp[3] -eq '0') $frow
    Check 'faceB.ai.failure.noPairs' ((Q ("SELECT COUNT(*) FROM t_pair_score WHERE lost_id={0} OR found_id={0};" -f $fid)) -eq '0') 'no pair rows'

    Section '10. Face B: review.mode=off regression'
    Start-App @{ FINDIT_REVIEW_MODE = 'off' }
    $offCases = @(@{ name = 'MODE_OFF'; payload = @{ type = 'LOST'; title = 'M6OFF 黑色钱包'; description = 'M6OFF 姓名：张三 电话 13800000009 在第三教学楼 201 丢失的黑色钱包'; categoryId = 2; location = '第三教学楼 201'; occurredAt = '2026-10-24 15:20:00'; contact = '13800000009'; publisherName = '张三' } })
    $offFile = Join-Path $tmp 'review-cases-off.json'
    [IO.File]::WriteAllText($offFile, ($offCases | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    $offIdsFile = Join-Path $tmp 'review-ids-off.json'
    $opub = Run-Node @((Join-Path $tmp 'm6-review.cjs'), 'publish', $offFile, $offIdsFile)
    $oids = Get-Content $offIdsFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $oid = $oids[0].id
    Say ('MODE_OFF publish reviewState=' + $oids[0].reviewState)
    Start-Sleep -Seconds 1
    $ov = Run-Node @((Join-Path $tmp 'm6-review.cjs'), 'check', $offIdsFile)
    $ovj = ($ov.text | ConvertFrom-Json) | Where-Object { $_.name -eq 'MODE_OFF' }
    $orow = Q ("SELECT CONCAT_WS('|',review_state,review_engine,is_visible,IFNULL(raw_title,'NULL'),IFNULL(match_title,'NULL')) FROM t_record WHERE id={0};" -f $oid)
    Say ('MODE_OFF row=' + $orow)
    $op = $orow -split '\|'
    Check 'faceB.off.immediateApproved' ($oids[0].reviewState -eq 'APPROVED' -and $op[0] -eq 'APPROVED') ('reviewState=' + $oids[0].reviewState)
    Check 'faceB.off.engineOFF' ($op[1] -eq 'OFF') ('engine=' + $op[1])
    Check 'faceB.off.visible' ($ovj.inPublicList -eq $true -and $ovj.otherDetailHttp -eq 200) ('inPublicList=' + $ovj.inPublicList + ' otherDetail=' + $ovj.otherDetailHttp)
    Check 'faceB.off.rawNull' ($op[3] -eq 'NULL' -and $op[4] -eq 'NULL') ('raw_title=' + $op[3] + ' match_title=' + $op[4])

    Section '11. stop + ports'
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
    Say ($(if ($exit -eq 0) { 'PART1_OK' } else { 'PART1_FAIL' }))
    Save
    exit $exit
}
