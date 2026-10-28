# Z85 review-gate real-package verification (single foreground run).
# Starts MySQL + stub LLM + the built jar, drives HTTP, asserts DB/API surfaces, stops everything.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root = 'C:\Project\hifox-workspaces\panshishi'
$repo = Join-Path $root 'work-v2-Z85'
$jar = Join-Path $repo 'server\target\find-it-campus-0.0.1-SNAPSHOT.jar'
$java = 'C:\Project\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe'
$mysqlBin = 'C:\Project\tools\mysql\mysql-8.0.29-winx64\bin'
$mysql = Join-Path $mysqlBin 'mysql.exe'
$mysqladmin = Join-Path $mysqlBin 'mysqladmin.exe'
$mysqld = Join-Path $mysqlBin 'mysqld.exe'
$dataDir = Join-Path $root 'mysql-b1-data'
$db = 'findit_z85'
$port = 18085
$base = "http://127.0.0.1:$port"
$evidencePath = Join-Path $root 'z85-evidence.txt'
$tmp = Join-Path $root 'z85-tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$lines = New-Object System.Collections.Generic.List[string]
function Say([string]$s) { $lines.Add($s); [Console]::Out.WriteLine($s) }
function Section([string]$s) { Say ''; Say ('===== ' + $s + ' =====') }
function Save { [IO.File]::WriteAllLines($evidencePath, $lines, (New-Object Text.UTF8Encoding($false))) }

function Q([string]$sql) {
    $out = & $mysql -u root -h 127.0.0.1 -P 3306 --default-character-set=utf8mb4 -N -B $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "mysql failed: $sql`n$out" }
    return (($out | Out-String).Trim())
}

function WriteJsonFile($obj, [string]$path) {
    $json = $obj | ConvertTo-Json -Depth 8 -Compress
    [IO.File]::WriteAllText($path, $json, (New-Object Text.UTF8Encoding($false)))
    return $path
}

function Call($method, $path, $cookieFile, $obj) {
    $out = Join-Path $tmp ('resp-' + [guid]::NewGuid().ToString('N') + '.json')
    $curlArgs = @('-s', '-o', $out, '-w', '%{http_code}', '-X', $method)
    if ($cookieFile) { $curlArgs += @('-b', $cookieFile) }
    if ($null -ne $obj) {
        $bodyFile = Join-Path $tmp ('body-' + [guid]::NewGuid().ToString('N') + '.json')
        WriteJsonFile $obj $bodyFile | Out-Null
        $curlArgs += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', "@$bodyFile")
    }
    $code = (& curl.exe @curlArgs ($base + $path) | Out-String).Trim()
    $text = if (Test-Path $out) { [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8) } else { '' }
    $json = $null
    try { $json = $text | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ status = [int]$code; text = $text; json = $json }
}

function Login([string]$studentNo, [string]$cookieFile) {
    $bodyFile = Join-Path $tmp ('login-' + [guid]::NewGuid().ToString('N') + '.json')
    WriteJsonFile @{ studentNo = $studentNo; password = 'Demo@123456' } $bodyFile | Out-Null
    $out = Join-Path $tmp ('login-resp-' + [guid]::NewGuid().ToString('N') + '.json')
    $code = (& curl.exe -s -c $cookieFile -o $out -w '%{http_code}' -X POST `
        -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$bodyFile" `
        ($base + '/api/auth/login') | Out-String).Trim()
    $text = if (Test-Path $out) { [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8) } else { '' }
    if ($code -ne '200') { throw "login $studentNo failed: $code $text" }
    return [pscustomobject]@{ status = 200; text = $text; json = ($text | ConvertFrom-Json) }
}

function CreateRecord($cookieFile, [string]$type, [string]$title, [string]$description, [string]$location, [string]$contact) {
    $body = @{
        type = $type
        title = $title
        description = $description
        location = $location
        occurredAt = '2026-10-20 15:00:00'
        contact = $contact
        publisherName = '李同学'
    }
    return (Call 'POST' '/api/records' $cookieFile $body)
}

function WaitHealth([int]$timeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $code = (& curl.exe -s -o NUL -w '%{http_code}' --max-time 2 ($base + '/api/health') | Out-String).Trim()
        if ($code -eq '200') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'app did not become healthy'
}

function StartApp([string]$mode, [string]$key) {
    $env:FINDIT_DB_URL = "jdbc:mysql://127.0.0.1:3306/$db`?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
    $env:FINDIT_DB_USER = 'root'
    $env:FINDIT_DB_PASSWORD = ''
    $env:FINDIT_REVIEW_MODE = $mode
    $env:DEEPSEEK_API_KEY = $key
    $env:FINDIT_LLM_BASE_URL = 'http://127.0.0.1:19090/v1'
    $env:FINDIT_LLM_MODEL = 'stub-model'
    $stdout = Join-Path $tmp "app-$mode.out.log"
    $stderr = Join-Path $tmp "app-$mode.err.log"
    $p = Start-Process -FilePath $java -ArgumentList '-jar', $jar, "--server.port=$port" `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    WaitHealth
    return $p
}

function StopApp($proc) {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        try { $proc.WaitForExit(10000) | Out-Null } catch { }
    }
}

$mysqldProc = $null
$stubProc = $null
$appProc = $null
$startedMysql = $false

try {
    Section '0. environment'
    Say ('repo=' + $repo)
    Say ('jar=' + $jar + ' bytes=' + (Get-Item $jar).Length)
    Say ('db=' + $db + ' port=' + $port)
    Say ('java=' + (Get-Item $java).VersionInfo.ProductVersion)

    Section '1. start MySQL 3306'
    $existing = Get-Process mysqld -ErrorAction SilentlyContinue
    if ($existing) {
        Say ('mysqld already running pid=' + (($existing | ForEach-Object Id) -join ','))
        $mysqldProc = $existing | Select-Object -First 1
    } else {
        Start-Process -FilePath $mysqld -ArgumentList "--datadir=$dataDir", '--port=3306', '--bind-address=127.0.0.1', '--console' `
            -RedirectStandardOutput (Join-Path $tmp 'mysql.out.log') -RedirectStandardError (Join-Path $tmp 'mysql.err.log') | Out-Null
        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            & $mysqladmin --host=127.0.0.1 --port=3306 --user=root ping *> $null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw 'mysqld not ready' }
        $startedMysql = $true
        $mysqldProc = (Get-Process mysqld | Sort-Object StartTime | Select-Object -Last 1)
    }
    $versionText = (& $mysql -u root -h 127.0.0.1 -P 3306 -N -B -e 'SELECT VERSION();' 2>&1 | Out-String).Trim()
    Say ('MYSQL_VERSION=' + $versionText)

    Section '2. reset schema + seed'
    $schema = [IO.File]::ReadAllText((Join-Path $repo 'db\schema.sql')).Replace('find_it_campus', $db)
    $seed = [IO.File]::ReadAllText((Join-Path $repo 'db\seed.sql')).Replace('find_it_campus', $db)
    $schemaFile = Join-Path $tmp 'schema.sql'
    $seedFile = Join-Path $tmp 'seed.sql'
    [IO.File]::WriteAllText($schemaFile, $schema, (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText($seedFile, $seed, (New-Object Text.UTF8Encoding($false)))
    cmd /c "`"$mysql`" -u root -h 127.0.0.1 -P 3306 --default-character-set=utf8mb4 < `"$schemaFile`"" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'schema failed' }
    cmd /c "`"$mysql`" -u root -h 127.0.0.1 -P 3306 --default-character-set=utf8mb4 < `"$seedFile`"" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'seed failed' }
    Say ('t_record=' + (Q 'SELECT COUNT(*) FROM t_record;'))
    Say ('visible=' + (Q 'SELECT COUNT(*) FROM t_record WHERE is_visible=1;'))

    Section '3. start stub LLM 19090'
    $stubProc = Start-Process -FilePath 'node' -ArgumentList (Join-Path $root 'z85-stub-llm.cjs') `
        -RedirectStandardOutput (Join-Path $tmp 'stub.out.log') -RedirectStandardError (Join-Path $tmp 'stub.err.log') -PassThru
    $stubReady = $false
    $stubProbeBody = WriteJsonFile @{ messages = @(@{ role = 'user'; content = 'ping' }) } (Join-Path $tmp 'stub-probe.json')
    for ($i = 0; $i -lt 40; $i++) {
        $probeCode = (& curl.exe -s -o NUL -w '%{http_code}' --max-time 2 -X POST `
            -H 'Content-Type: application/json' --data-binary "@$stubProbeBody" `
            'http://127.0.0.1:19090/v1/chat/completions' | Out-String).Trim()
        if ($probeCode -eq '200') { $stubReady = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $stubReady) { throw 'stub llm not ready' }
    Say 'stub llm ready'

    Section '4. mode=ai: publish PENDING / APPROVED / REJECT paths'
    $appProc = StartApp 'ai' 'stub-key'
    Say ('app pid=' + $appProc.Id + ' mode=ai')
    $c1 = Join-Path $tmp 'cookie1.txt'
    $c2 = Join-Path $tmp 'cookie2.txt'
    Login 'demo01' $c1 | Out-Null
    Login 'demo02' $c2 | Out-Null

    $pending = CreateRecord $c1 'LOST' 'Z85PEND STUB_FAIL 黑色钱包' 'Z85PEND STUB_FAIL 姓名：张三 电话 13800000009 在第三教学楼 201 丢失的黑色皮质钱包，内有工行卡' '第三教学楼 201' '13800000009'
    $approved = CreateRecord $c1 'LOST' 'Z85APPROVE 黑色钱包 姓名：张三' 'Z85APPROVE 姓名：张三 电话 13800000009 在第三教学楼 201 丢失的黑色皮质钱包，内有工行卡' '第三教学楼 201' '13800000009'
    $sensitive = CreateRecord $c1 'LOST' 'Z85SENSITIVE 手机号' 'Z85SENSITIVE STUB_SENSITIVE 测试规则双检' '第三教学楼 201' '13800000009'
    $rejected = CreateRecord $c1 'LOST' 'Z85REJECT 无法去身份' 'Z85REJECT STUB_REJECT 测试模型拒绝' '第三教学楼 201' '13800000009'
    $failed = CreateRecord $c1 'LOST' 'Z85FAIL 审核不可用' 'Z85FAIL STUB_FAIL 测试连续失败' '第三教学楼 201' '13800000009'

    foreach ($pair in @(@('pending', $pending), @('approved', $approved), @('sensitive', $sensitive), @('rejected', $rejected), @('failed', $failed))) {
        $name = $pair[0]; $resp = $pair[1]
        Say ("{0}: http={1} id={2} reviewState={3}" -f $name, $resp.status, $resp.json.data.id, $resp.json.data.reviewState)
        if ($resp.status -ne 200) { throw "create $name failed: $($resp.text)" }
    }
    $pendingId = [long]$pending.json.data.id
    $approvedId = [long]$approved.json.data.id
    $sensitiveId = [long]$sensitive.json.data.id
    $rejectedId = [long]$rejected.json.data.id
    $failedId = [long]$failed.json.data.id

    Start-Sleep -Seconds 6

    Section '5. DB state after async review'
    Say (Q ("SELECT id,review_state,review_engine,review_model,review_attempts,review_manual_required,is_visible FROM t_record WHERE id IN ({0},{1},{2},{3},{4}) ORDER BY id;" -f $pendingId,$approvedId,$sensitiveId,$rejectedId,$failedId))
    Say (Q ("SELECT id,review_reason FROM t_record WHERE id IN ({0},{1},{2},{3}) ORDER BY id;" -f $pendingId,$sensitiveId,$rejectedId,$failedId))

    Section '6. PENDING four surfaces'
    $pub = Call 'GET' ('/api/records?keyword=Z85PEND&pageSize=50') $null $null
    Say ('public list status=' + $pub.status + ' total=' + $pub.json.data.total)
    if ($pub.json.data.total -ne 0) { throw 'PENDING record visible in public list' }

    $match = Call 'POST' '/api/match' $c1 @{ text = 'Z85PEND 黑色钱包'; type = 'FOUND'; topN = 20; minScore = 0.0 }
    $matchHit = @($match.json.data.items | Where-Object { [long]$_.recordId -eq $pendingId }).Count
    Say ('/api/match status=' + $match.status + ' items=' + $match.json.data.total + ' pendingHit=' + $matchHit)
    if ($matchHit -ne 0) { throw 'PENDING record hit /api/match' }

    $otherDetail = Call 'GET' ("/api/records/$pendingId") $c2 $null
    Say ('other detail status=' + $otherDetail.status + ' code=' + $otherDetail.json.code)
    if ($otherDetail.status -ne 404) { throw 'other user can read PENDING detail' }

    $otherMine = Call 'GET' '/api/my/records?status=PENDING&pageSize=50' $c2 $null
    $otherMineHit = @($otherMine.json.data.list | Where-Object { [long]$_.id -eq $pendingId }).Count
    Say ('other /api/my/records pendingHit=' + $otherMineHit)
    if ($otherMineHit -ne 0) { throw 'other user sees PENDING in my records' }

    $ownMine = Call 'GET' '/api/my/records?status=PENDING&pageSize=50' $c1 $null
    $ownItem = @($ownMine.json.data.list | Where-Object { [long]$_.id -eq $pendingId })[0]
    Say ('owner /api/my/records reviewState=' + $ownItem.reviewState + ' manual=' + $ownItem.reviewManualRequired + ' reason=' + $ownItem.reviewReason)
    if ($ownItem.reviewState -ne 'PENDING') { throw 'owner does not see review state' }

    $pendingPairs = Q ("SELECT COUNT(*) FROM t_pair_score WHERE lost_id={0} OR found_id={0};" -f $pendingId)
    Say ('pending pair rows=' + $pendingPairs)
    if ([int]$pendingPairs -ne 0) { throw 'PENDING record got scored' }

    Section '7. APPROVED path'
    $pubA = Call 'GET' ('/api/records?keyword=Z85APPROVE&pageSize=50') $null $null
    $pubAHit = @($pubA.json.data.list | Where-Object { [long]$_.id -eq $approvedId }).Count
    Say ('approved public total=' + $pubA.json.data.total + ' hit=' + $pubAHit)
    if ($pubAHit -ne 1) { throw 'APPROVED record not visible' }

    $detailA = Call 'GET' ("/api/records/$approvedId") $c2 $null
    Say ('approved other detail status=' + $detailA.status + ' hasRaw=' + ($detailA.text -match 'rawTitle') + ' hasMatch=' + ($detailA.text -match 'matchTitle'))
    if ($detailA.status -ne 200) { throw 'APPROVED detail not visible to other' }
    if ($detailA.text -match 'rawTitle|rawDescription|matchTitle|matchDescription') { throw 'raw/match leaked in public response' }

    $approvedPairs = Q ("SELECT COUNT(*) FROM t_pair_score WHERE lost_id={0} OR found_id={0};" -f $approvedId)
    Say ('approved pair rows=' + $approvedPairs)
    if ([int]$approvedPairs -lt 1) { throw 'APPROVED record did not trigger scoring' }

    Section '8. REJECT paths'
    $sensitiveRow = Q ("SELECT review_state, review_engine, review_reason FROM t_record WHERE id={0};" -f $sensitiveId)
    Say ('sensitive: ' + $sensitiveRow)
    if ($sensitiveRow -notmatch 'REJECTED') { throw 'rule double-check did not reject' }
    if ($sensitiveRow -notmatch '规则双检命中') { throw 'rule double-check reason missing' }
    $rejectedRow = Q ("SELECT review_state, review_reason FROM t_record WHERE id={0};" -f $rejectedId)
    Say ('model reject: ' + $rejectedRow)
    if ($rejectedRow -notmatch 'REJECTED') { throw 'model REJECT not persisted' }

    $pubR = Call 'GET' ('/api/records?keyword=Z85SENSITIVE&pageSize=50') $null $null
    Say ('rejected public total=' + $pubR.json.data.total)
    if ($pubR.json.data.total -ne 0) { throw 'REJECTED record visible' }

    Section '9. AI failure path'
    $failedRow = Q ("SELECT review_state, review_attempts, review_manual_required, is_visible FROM t_record WHERE id={0};" -f $failedId)
    Say ('failed: ' + $failedRow)
    if ($failedRow -notmatch "PENDING`t3`t1`t0") { throw 'AI failure did not stop in PENDING + manual' }
    $failedPairs = Q ("SELECT COUNT(*) FROM t_pair_score WHERE lost_id={0} OR found_id={0};" -f $failedId)
    Say ('failed pair rows=' + $failedPairs)
    if ([int]$failedPairs -ne 0) { throw 'failed record got scored' }

    Section '10. double-channel columns + audit'
    Say ('approved display/raw/match:')
    Say (Q ("SELECT title,description,location,raw_title,raw_description,match_title,match_description FROM t_record WHERE id={0}\G" -f $approvedId))
    $display = Q ("SELECT CONCAT_WS('|',title,description,location) FROM t_record WHERE id={0};" -f $approvedId)
    if ($display -match '13800000009|张三|201') { throw 'display text still contains identity/precise location' }
    $raw = Q ("SELECT CONCAT_WS('|',raw_title,raw_description,raw_location) FROM t_record WHERE id={0};" -f $approvedId)
    Say ('raw contains identity=' + ($raw -match '13800000009'))
    if ($raw -notmatch '13800000009') { throw 'raw text was not preserved' }
    $matchText = Q ("SELECT CONCAT_WS('|',match_title,match_description,match_location) FROM t_record WHERE id={0};" -f $approvedId)
    Say ('match contains phone=' + ($matchText -match '13800000009') + ' keeps location=' + ($matchText -match '201'))
    if ($matchText -match '13800000009') { throw 'match text still contains phone' }
    if ($matchText -notmatch '201') { throw 'match text lost location detail' }

    Say ('audit rows approved=' + (Q ("SELECT COUNT(*) FROM t_review_audit WHERE record_id={0};" -f $approvedId)))
    Say (Q ("SELECT action,decision,review_state,engine,model,prompt_version,latency_ms,prompt_tokens,completion_tokens FROM t_review_audit WHERE record_id={0} ORDER BY id;" -f $approvedId))
    Say ('audit rows failed=' + (Q ("SELECT COUNT(*) FROM t_review_audit WHERE record_id={0};" -f $failedId)))
    $auditCols = Q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='findit_z85' AND table_name='t_review_audit' AND column_name IN ('title','description','location','raw_title');"
    Say ('audit text columns=' + $auditCols)
    if ([int]$auditCols -ne 0) { throw 'audit table contains raw text columns' }

    Section '11. stop ai app, stop stub, mode=off regression'
    StopApp $appProc
    $appProc = $null
    if ($stubProc -and -not $stubProc.HasExited) { Stop-Process -Id $stubProc.Id -Force -ErrorAction SilentlyContinue }
    $stubProc = $null

    $appProc = StartApp 'off' ''
    Say ('app pid=' + $appProc.Id + ' mode=off (stub LLM down)')
    $c1off = Join-Path $tmp 'cookie1off.txt'
    Login 'demo01' $c1off | Out-Null
    $off = CreateRecord $c1off 'LOST' 'Z85OFF 黑色钱包' 'Z85OFF 姓名：张三 电话 13800000009 在第三教学楼 201 丢失的黑色钱包' '第三教学楼 201' '13800000009'
    Say ('off create http=' + $off.status + ' id=' + $off.json.data.id + ' reviewState=' + $off.json.data.reviewState)
    if ($off.json.data.reviewState -ne 'APPROVED') { throw 'mode=off did not approve immediately' }
    $offId = [long]$off.json.data.id
    Start-Sleep -Seconds 1
    $pubOff = Call 'GET' '/api/records?keyword=Z85OFF&pageSize=50' $null $null
    Say ('off public total=' + $pubOff.json.data.total)
    if ($pubOff.json.data.total -lt 1) { throw 'mode=off record not visible' }
    $c2off = Join-Path $tmp 'cookie2off.txt'
    Login 'demo02' $c2off | Out-Null
    $offDetail = Call 'GET' ("/api/records/$offId") $c2off $null
    Say ('off other detail status=' + $offDetail.status)
    if ($offDetail.status -ne 200) { throw 'mode=off record not readable by other' }
    Say ('off DB: ' + (Q ("SELECT review_state,review_engine,is_visible,IFNULL(raw_title,'NULL'),IFNULL(match_title,'NULL') FROM t_record WHERE id={0};" -f $offId)))

    Section '12. stop app + mysql'
    StopApp $appProc
    $appProc = $null
    if ($stubProc -and -not $stubProc.HasExited) { Stop-Process -Id $stubProc.Id -Force -ErrorAction SilentlyContinue }
    if ($startedMysql) {
        try { & $mysqladmin --host=127.0.0.1 --port=3306 --user=root shutdown *> $null; Say 'mysqld shutdown requested' } catch { Say ('mysqld shutdown failed: ' + $_.Exception.Message) }
    }
    Section 'RESULT'
    Say 'ALL Z85 CHECKS PASSED'
    Save
    exit 0
} catch {
    Section 'FAILURE'
    Say ($_.Exception.Message)
    Say ($_.ScriptStackTrace)
    Save
    if ($appProc) { StopApp $appProc }
    if ($stubProc -and -not $stubProc.HasExited) { Stop-Process -Id $stubProc.Id -Force -ErrorAction SilentlyContinue }
    if ($startedMysql) { try { & $mysqladmin --host=127.0.0.1 --port=3306 --user=root shutdown *> $null } catch { } }
    exit 1
}
