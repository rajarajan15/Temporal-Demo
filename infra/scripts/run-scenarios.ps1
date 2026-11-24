<#
.SYNOPSIS
  Runs the Milestone 2 and 3 scenarios against real Temporal, mock, worker and API processes.

.DESCRIPTION
  Starts its own copy of every service on separate ports with a fresh database, runs each
  scenario, prints PASS or FAIL, and stops only the processes it started. Your normal dev
  services on the standard ports are not touched. Build first with infra\scripts\build.cmd.

  Exit code 0 means every scenario passed.
#>
param(
    [int]$TemporalPort = 17233,
    [int]$MockPort = 18091,
    [int]$ApiPort = 18080,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$tools = Join-Path $root '.tools'
$logs = Join-Path $root 'target\scenarios'
$api = "http://127.0.0.1:$ApiPort"
$mock = "http://127.0.0.1:$MockPort"
$address = "127.0.0.1:$TemporalPort"
$confirmationTimeoutSeconds = 5
$results = New-Object System.Collections.ArrayList
$procs = @{}

# ---------- prerequisites ----------

$jdk = Get-ChildItem "$tools\jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
$temporal = Join-Path $tools 'temporal\temporal.exe'
$jars = @{
    mock   = Join-Path $root 'mock-services\target\mock-services-0.1.0-SNAPSHOT-exec.jar'
    worker = Join-Path $root 'worker-app\target\worker-app-0.1.0-SNAPSHOT-exec.jar'
    api    = Join-Path $root 'api-app\target\api-app-0.1.0-SNAPSHOT-exec.jar'
}
if (-not $jdk -or -not (Test-Path $temporal)) {
    Write-Host 'The local toolchain in .tools is missing (JDK or Temporal CLI).' -ForegroundColor Red
    exit 2
}
$java = Join-Path $jdk.FullName 'bin\java.exe'
foreach ($jar in $jars.Values) {
    if (-not (Test-Path $jar)) {
        Write-Host "Missing $jar. Run infra\scripts\build.cmd first." -ForegroundColor Red
        exit 2
    }
}
$ports = @($TemporalPort, ($TemporalPort + 10), ($TemporalPort + 11), $MockPort, $ApiPort)
$busy = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })
if ($busy.Count -gt 0) {
    Write-Host "Port(s) already in use: $(($busy.LocalPort | Sort-Object -Unique) -join ', '). Stop the previous run or pass other ports." -ForegroundColor Red
    exit 2
}

# ---------- helpers ----------

function Wait-Port([int]$port, [int]$seconds) {
    $end = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $end) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

function Start-Service([string]$name, [string]$exe, [string[]]$arguments) {
    $p = Start-Process -PassThru -WindowStyle Hidden -WorkingDirectory $root $exe -ArgumentList $arguments `
        -RedirectStandardOutput "$logs\$name.out" -RedirectStandardError "$logs\$name.err"
    $procs[$name] = $p
    return $p
}

function Stop-ServiceProcess([string]$name) {
    $p = $procs[$name]
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -Confirm:$false -ErrorAction SilentlyContinue }
    $procs.Remove($name)
}

function Start-Worker([string]$name) {
    Start-Service $name $java @('-jar', $jars.worker) | Out-Null
}

function Invoke-Http([string]$method, [string]$url, [hashtable]$headers, $body) {
    try {
        $request = @{ UseBasicParsing = $true; Method = $method; Uri = $url; TimeoutSec = 30 }
        if ($headers) { $request.Headers = $headers }
        if ($null -ne $body) { $request.ContentType = 'application/json'; $request.Body = ($body | ConvertTo-Json -Compress) }
        $response = Invoke-WebRequest @request
        return @{ code = [int]$response.StatusCode; body = $response.Content }
    } catch {
        $code = 0
        $content = ''
        if ($_.Exception.Response) {
            $code = [int]$_.Exception.Response.StatusCode
            try { $content = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { }
        }
        return @{ code = $code; body = $content }
    }
}

function Get-Code($response) {
    if (-not $response.body) { return '' }
    try { return ($response.body | ConvertFrom-Json).code } catch { return '' }
}

# Windows PowerShell returns a JSON array as one object; this unrolls it.
function ConvertTo-List([string]$json) {
    if (-not $json) { return @() }
    return @(($json | ConvertFrom-Json) | ForEach-Object { $_ })
}

function Reset-Mocks { Invoke-Http POST "$mock/mock-admin/reset" $null $null | Out-Null }
function Set-Failure([string]$tenant, [string]$target, $rule) { Invoke-Http PUT "$mock/mock-admin/failures/$tenant/$target" $null $rule | Out-Null }
function Set-Callbacks([string]$tenant, [string]$mode, $delayMs = $null) {
    Invoke-Http PUT "$mock/mock-admin/callbacks/$tenant" $null @{ mode = $mode; delayMs = $delayMs } | Out-Null
}
function New-Orphan([string]$tenant) { ((Invoke-Http POST "$mock/mock-admin/cards/$tenant/orphan" $null $null).body | ConvertFrom-Json).cardId }
function Get-Cards([string]$tenant) { ConvertTo-List (Invoke-Http GET "$mock/mock-admin/cards/$tenant" $null $null).body }
function Get-Counters([string]$tenant) { (Invoke-Http GET "$mock/mock-admin/counters/$tenant" $null $null).body | ConvertFrom-Json }

function New-CardRequest([string]$key, [hashtable]$changes = @{}, [string]$tenant = 'bank-a') {
    $body = [ordered]@{
        cardType        = 'VIRTUAL'
        productCode     = 'VIRTUAL_STANDARD'
        customerRef     = 'customer-1'
        accountRef      = 'account-1'
        spendLimitMinor = 50000
    }
    foreach ($k in $changes.Keys) { $body[$k] = $changes[$k] }
    return Invoke-Http POST "$api/v1/card-requests" @{ 'X-Tenant-Id' = $tenant; 'Idempotency-Key' = $key } $body
}

function Get-Progress([string]$key, [string]$tenant = 'bank-a', [string]$kind = 'card-requests') {
    return Invoke-Http GET "$api/v1/$kind/$key" @{ 'X-Tenant-Id' = $tenant } $null
}

function Send-Review([string]$key, [string]$action, [string]$tenant = 'bank-a', [string]$updateKey = $null) {
    $headers = @{ 'X-Tenant-Id' = $tenant }
    if ($updateKey) { $headers['Idempotency-Key'] = $updateKey }
    return Invoke-Http POST "$api/v1/card-requests/$key/approvals" $headers @{ action = $action; reviewer = 'ops-1' }
}

function Send-Cancel([string]$key, [string]$tenant = 'bank-a', [string]$reason = 'customer-request') {
    return Invoke-Http POST "$api/v1/card-requests/$key/cancel" @{ 'X-Tenant-Id' = $tenant } @{ reason = $reason }
}

# Polls progress until the condition holds or the timeout passes, and returns the last progress read.
function Wait-Progress([string]$key, [scriptblock]$condition, [string]$tenant = 'bank-a', [string]$kind = 'card-requests') {
    $end = (Get-Date).AddSeconds($TimeoutSeconds)
    $last = $null
    while ((Get-Date) -lt $end) {
        $r = Get-Progress $key $tenant $kind
        if ($r.code -eq 200) {
            $last = $r.body | ConvertFrom-Json
            if (& $condition $last) { return $last }
        }
        Start-Sleep -Milliseconds 500
    }
    return $last
}

function Wait-Card([string]$key, [string]$tenant = 'bank-a') { Wait-Progress $key { param($p) $p.finished -eq $true } $tenant }
function Wait-Parked([string]$key, [string]$tenant = 'bank-a') { Wait-Progress $key { param($p) $null -ne $p.pendingReview } $tenant }

function Get-StepState($progress, [string]$name) {
    return ($progress.steps | Where-Object { $_.name -eq $name } | Select-Object -First 1).state
}

function Get-HistoryJson([string]$workflowId) {
    return (& $temporal workflow show -w $workflowId --address $address -o json 2>$null) -join "`n"
}

function Get-Events([string]$workflowId) {
    return @((Get-HistoryJson $workflowId | ConvertFrom-Json).events)
}

function Get-ActivityOrder([string]$workflowId) {
    return @(Get-Events $workflowId |
        Where-Object { $_.eventType -match 'ACTIVITY_TASK_SCHEDULED' } |
        ForEach-Object { $_.activityTaskScheduledEventAttributes.activityType.name })
}

function Get-SignalCount([string]$workflowId) {
    return @(Get-Events $workflowId | Where-Object { $_.eventType -match 'WORKFLOW_EXECUTION_SIGNALED' }).Count
}

function Find-Workflows([string]$query) {
    return @(& $temporal workflow list --address $address --query $query -o json 2>$null | ConvertFrom-Json | ForEach-Object { $_ })
}

function Wait-Reconciliation([string]$key, [string]$tenant = 'bank-a') {
    $end = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $end) {
        $r = Invoke-Http GET "$api/v1/reconciliations/$key" @{ 'X-Tenant-Id' = $tenant } $null
        if ($r.code -eq 200) {
            $body = $r.body | ConvertFrom-Json
            if ($body.status -ne 'RUNNING') { return $body }
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Test-Scenario([string]$id, [string]$name, [scriptblock]$body) {
    Write-Host ("{0,-5} {1} ..." -f $id, $name) -NoNewline
    $started = Get-Date
    $detail = ''
    $ok = $false
    try {
        $outcome = & $body
        $ok = [bool]$outcome.ok
        $detail = $outcome.detail
    } catch {
        $detail = "error: $($_.Exception.Message)"
    }
    $seconds = [int]((Get-Date) - $started).TotalSeconds
    if ($ok) { Write-Host " PASS ($seconds s)" -ForegroundColor Green } else { Write-Host " FAIL ($seconds s)" -ForegroundColor Red; Write-Host "      $detail" -ForegroundColor Yellow }
    [void]$results.Add([pscustomobject]@{ Id = $id; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Scenario = $name; Seconds = $seconds; Detail = $detail })
}

function Result([bool]$ok, [string]$detail) { return @{ ok = $ok; detail = $detail } }

# ---------- start services ----------

if (Test-Path $logs) { Remove-Item $logs -Recurse -Force -Confirm:$false -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force $logs | Out-Null

$env:TEMPORAL_ADDRESS = $address
$env:MOCK_PORT = "$MockPort"
$env:MOCK_BASE_URL = $mock
$env:MOCK_CALLBACK_URL = "$api/v1/webhooks/processor"
$env:API_PORT = "$ApiPort"
$env:CONFIRMATION_TIMEOUT_SECONDS = "$confirmationTimeoutSeconds"

$exitCode = 1
try {
    Write-Host "Starting services (logs in $logs) ..."
    $temporalArgs = @('server', 'start-dev', '--headless', '--log-level', 'error',
        '--port', "$TemporalPort", '--http-port', "$($TemporalPort + 10)", '--metrics-port', "$($TemporalPort + 11)",
        '--db-filename', "$logs\temporal.db",
        '--search-attribute', 'TenantId=Keyword', '--search-attribute', 'CardId=Keyword',
        '--search-attribute', 'CardType=Keyword', '--search-attribute', 'CardStatus=Keyword',
        '--search-attribute', 'RequestId=Keyword')
    Start-Service 'temporal' $temporal $temporalArgs | Out-Null
    if (-not (Wait-Port $TemporalPort 60)) { throw 'Temporal did not start.' }
    Start-Service 'mock' $java @('-jar', $jars.mock) | Out-Null
    Start-Worker 'worker'
    Start-Service 'api' $java @('-jar', $jars.api) | Out-Null
    if (-not (Wait-Port $MockPort 90)) { throw 'Mock services did not start.' }
    if (-not (Wait-Port $ApiPort 90)) { throw 'API did not start.' }
    Start-Sleep -Seconds 5
    if ($procs['worker'].HasExited) { throw 'Worker exited during startup. See worker.out.' }
    Write-Host ''

    # =====================================================================
    # Milestone 2: issuance, retries, idempotency and compensation
    # =====================================================================

    Test-Scenario 'tc01' 'Happy path, callback arrives normally' {
        Reset-Mocks
        $post = New-CardRequest 'tc01'
        $p = Wait-Card 'tc01'
        $cards = @(Get-Cards 'bank-a')
        $done = @($p.steps | Where-Object { $_.state -eq 'DONE' }).Count
        $order = (Get-ActivityOrder 'card-bank-a-tc01') -join ','
        $expected = 'ValidateRequest,RunKyc,CreateCard,SetLimits,ActivateCard,SendNotification'
        $signals = Get-SignalCount 'card-bank-a-tc01'
        Result ($post.code -eq 201 -and $p.status -eq 'ACTIVE' -and $done -eq 7 -and $p.card.cardId -and $signals -eq 1 -and
            $cards.Count -eq 1 -and $cards[0].status -eq 'ACTIVE' -and $cards[0].limitMinor -eq 50000 -and $order -eq $expected) `
            "http=$($post.code) status=$($p.status) doneSteps=$done signals=$signals cards=$($cards.Count) order=$order"
    }

    Test-Scenario 'tc01' 'History holds no card numbers' {
        $history = Get-HistoryJson 'card-bank-a-tc01'
        $decoded = ([regex]::Matches($history, '"data":\s*"([A-Za-z0-9+/=]+)"') | ForEach-Object {
                try { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_.Groups[1].Value)) } catch { '' }
            }) -join ' '
        $leak = [regex]::Match("$history $decoded", '(?<!\d)\d{13,19}(?!\d)')
        Result ($decoded.Length -gt 0 -and -not $leak.Success) "payloadChars=$($decoded.Length) match=$($leak.Value)"
    }

    Test-Scenario 'tc01' 'Search attributes are queryable' {
        $found = @(Find-Workflows "TenantId='bank-a' AND CardType='VIRTUAL' AND CardStatus='ACTIVE' AND RequestId='tc01'")
        Result ($found.Count -eq 1) "matches=$($found.Count)"
    }

    Test-Scenario 'tc02' 'Duplicate request returns the existing one' {
        $post = New-CardRequest 'tc01'
        $cards = @(Get-Cards 'bank-a')
        Result ($post.code -eq 200 -and $cards.Count -eq 1) "http=$($post.code) cards=$($cards.Count)"
    }

    Test-Scenario 'tc03' 'Other tenant cannot see the request' {
        $r = Get-Progress 'tc01' 'bank-b'
        Result ($r.code -eq 404) "http=$($r.code)"
    }

    Test-Scenario 'tc04' 'Card number as input is refused' {
        $post = New-CardRequest 'tc04' @{ customerRef = '4111111111111111' }
        $r = Get-Progress 'tc04'
        Result ($post.code -eq 400 -and $r.code -eq 404) "post=$($post.code) get=$($r.code)"
    }

    Test-Scenario 'tc05' 'Unknown product is rejected' {
        $post = New-CardRequest 'tc05' @{ productCode = 'GOLD' }
        $p = Wait-Card 'tc05'
        Result ($post.code -eq 201 -and $p.status -eq 'REJECTED' -and $p.reasonCode -eq 'INVALID_PRODUCT') "status=$($p.status) reason=$($p.reasonCode)"
    }

    Test-Scenario 'tc06' 'Limit above policy is rejected' {
        $post = New-CardRequest 'tc06' @{ spendLimitMinor = 2000000 }
        $p = Wait-Card 'tc06'
        Result ($post.code -eq 201 -and $p.status -eq 'REJECTED' -and $p.reasonCode -eq 'LIMIT_EXCEEDS_POLICY') "status=$($p.status) reason=$($p.reasonCode)"
    }

    Test-Scenario 'tc07' 'KYC rejected, no card created' {
        Reset-Mocks
        New-CardRequest 'tc07' @{ customerRef = 'reject-1' } | Out-Null
        $p = Wait-Card 'tc07'
        $cards = @(Get-Cards 'bank-a')
        Result ($p.status -eq 'REJECTED' -and $p.reasonCode -eq 'KYC_REJECTED' -and $cards.Count -eq 0 -and
            (Get-StepState $p 'createCard') -eq 'PENDING') "status=$($p.status) reason=$($p.reasonCode) cards=$($cards.Count)"
    }

    Test-Scenario 'tc08' 'KYC needs review: parks, reviewer rejects' {
        New-CardRequest 'tc08' @{ customerRef = 'review-1' } | Out-Null
        $parked = Wait-Parked 'tc08'
        $review = Send-Review 'tc08' 'REJECT'
        $p = Wait-Card 'tc08'
        Result ($parked.status -eq 'UNDER_REVIEW' -and $parked.pendingReview.kind -eq 'KYC_REVIEW' -and $review.code -eq 200 -and
            $p.status -eq 'REJECTED' -and $p.reasonCode -eq 'REJECTED_BY_REVIEWER') `
            "parked=$($parked.pendingReview.kind) review=$($review.code) final=$($p.status)/$($p.reasonCode)"
    }

    Test-Scenario 'tc09' 'Processor fails twice, then recovers' {
        Reset-Mocks
        Set-Failure 'bank-a' 'processor/createCard' @{ mode = 'SERVER_ERROR'; times = 2 }
        New-CardRequest 'tc09' | Out-Null
        $p = Wait-Card 'tc09'
        $n = Get-Counters 'bank-a'
        $cards = @(Get-Cards 'bank-a')
        Result ($p.status -eq 'ACTIVE' -and $n.calls.'processor/createCard' -eq 3 -and $n.sideEffects.'processor/createCard' -eq 1 -and $cards.Count -eq 1) `
            "status=$($p.status) calls=$($n.calls.'processor/createCard') sideEffects=$($n.sideEffects.'processor/createCard') cards=$($cards.Count)"
    }

    Test-Scenario 'tc10' 'Response lost after success, still one card' {
        Reset-Mocks
        Set-Failure 'bank-a' 'processor/createCard' @{ mode = 'DROP_RESPONSE'; times = 1 }
        New-CardRequest 'tc10' | Out-Null
        $p = Wait-Card 'tc10'
        $n = Get-Counters 'bank-a'
        $cards = @(Get-Cards 'bank-a')
        Result ($p.status -eq 'ACTIVE' -and $n.calls.'processor/createCard' -ge 2 -and $n.sideEffects.'processor/createCard' -eq 1 -and $cards.Count -eq 1) `
            "status=$($p.status) calls=$($n.calls.'processor/createCard') sideEffects=$($n.sideEffects.'processor/createCard') cards=$($cards.Count)"
    }

    Test-Scenario 'tc11' 'Activation declined, compensated in reverse order' {
        Reset-Mocks
        Set-Failure 'bank-a' 'processor/activateCard' @{ mode = 'BUSINESS_ERROR'; times = -1; code = 'ACTIVATION_DECLINED' }
        New-CardRequest 'tc11' | Out-Null
        $p = Wait-Card 'tc11'
        $cards = @(Get-Cards 'bank-a')
        $tail = ((Get-ActivityOrder 'card-bank-a-tc11') | Select-Object -Last 4) -join ','
        $compensated = @($p.steps | Where-Object { $_.state -eq 'COMPENSATED' }).Count
        Result ($p.status -eq 'REJECTED' -and $p.reasonCode -eq 'ACTIVATION_DECLINED' -and $compensated -eq 3 -and
            $tail -eq 'ActivateCard,DeactivateCard,ResetLimits,CancelCard' -and
            $cards.Count -eq 1 -and $cards[0].status -eq 'CANCELLED' -and $cards[0].limitMinor -eq 0) `
            "status=$($p.status) reason=$($p.reasonCode) compensated=$compensated order=$tail card=$($cards[0].status)"
    }

    Test-Scenario 'tc12' 'Notification down, card still issued' {
        Reset-Mocks
        Set-Failure 'bank-a' 'notification/send' @{ mode = 'OFFLINE'; times = -1 }
        New-CardRequest 'tc12' | Out-Null
        $p = Wait-Card 'tc12'
        $cards = @(Get-Cards 'bank-a')
        $compensated = @($p.steps | Where-Object { $_.state -eq 'COMPENSATED' }).Count
        Result ($p.status -eq 'ACTIVE' -and (Get-StepState $p 'sendNotification') -eq 'FAILED' -and $compensated -eq 0 -and $cards[0].status -eq 'ACTIVE') `
            "status=$($p.status) notification=$(Get-StepState $p 'sendNotification') compensated=$compensated"
    }

    Test-Scenario 'tc13' 'Retries run out: parks, bank B unaffected (tc14), operator retries' {
        Reset-Mocks
        Set-Failure 'bank-a' 'processor/createCard' @{ mode = 'OFFLINE'; times = -1 }
        New-CardRequest 'tc13' @{} 'bank-a' | Out-Null
        New-CardRequest 'tc16' @{} 'bank-a' | Out-Null
        $startB = Get-Date
        New-CardRequest 'tc14' @{} 'bank-b' | Out-Null
        $b = Wait-Card 'tc14' 'bank-b'
        $bSeconds = [int]((Get-Date) - $startB).TotalSeconds
        $parked = Wait-Parked 'tc13'
        Set-Failure 'bank-a' 'processor/createCard' @{ mode = 'OFFLINE'; times = 0 }
        $review = Send-Review 'tc13' 'RETRY'
        $a = Wait-Card 'tc13'
        Result ($b.status -eq 'ACTIVE' -and $bSeconds -le 15 -and $parked.pendingReview.kind -eq 'RETRIES_EXHAUSTED' -and
            $parked.status -eq 'UNDER_REVIEW' -and $review.code -eq 200 -and $a.status -eq 'ACTIVE') `
            "bank-b=$($b.status) in $bSeconds s, parked=$($parked.pendingReview.kind) review=$($review.code) final=$($a.status)"
    }

    Test-Scenario 'tc16' 'Retries run out: operator cancels, compensation runs' {
        $parked = Wait-Parked 'tc16'
        $review = Send-Review 'tc16' 'CANCEL'
        $p = Wait-Card 'tc16'
        $cancelled = (Get-StepState $p 'cancelCard') -eq 'COMPENSATED'
        Result ($parked.pendingReview.kind -eq 'RETRIES_EXHAUSTED' -and $review.code -eq 200 -and $p.status -eq 'CANCELLED' -and
            $p.reasonCode -eq 'CANCELLED_BY_OPERATOR' -and $cancelled) "review=$($review.code) final=$($p.status)/$($p.reasonCode) cancelCard=$(Get-StepState $p 'cancelCard')"
    }

    Test-Scenario 'tc15' 'Worker crash, then recovery' {
        Reset-Mocks
        Stop-ServiceProcess 'worker'
        Start-Sleep -Seconds 1
        $post = New-CardRequest 'tc15'
        $whileDown = Get-Progress 'tc15'
        Start-Worker 'worker'
        $p = Wait-Card 'tc15'
        $cards = @(Get-Cards 'bank-a')
        Result ($post.code -eq 201 -and $whileDown.code -eq 503 -and $p.status -eq 'ACTIVE' -and $cards.Count -eq 1) `
            "post=$($post.code) whileDown=$($whileDown.code) after=$($p.status) cards=$($cards.Count)"
    }

    # =====================================================================
    # Milestone 3: signals, timers, updates, reissue, heartbeats, schedules
    # =====================================================================

    Test-Scenario 'tc17' 'Late callback: timer parks the request, the late signal clears it' {
        Reset-Mocks
        Set-Callbacks 'bank-a' 'DELAY' (($confirmationTimeoutSeconds + 4) * 1000)
        New-CardRequest 'tc17' | Out-Null
        $parked = Wait-Parked 'tc17'
        $p = Wait-Card 'tc17'
        Result ($parked.pendingReview.kind -eq 'CONFIRMATION_TIMEOUT' -and $parked.status -eq 'UNDER_REVIEW' -and $p.status -eq 'ACTIVE') `
            "parked=$($parked.pendingReview.kind) final=$($p.status)"
    }

    Test-Scenario 'tc18' 'Callback never arrives: operator approves' {
        Reset-Mocks
        Set-Callbacks 'bank-a' 'NEVER'
        New-CardRequest 'tc18' | Out-Null
        $parked = Wait-Parked 'tc18'
        $wrong = Send-Review 'tc18' 'RETRY'
        $review = Send-Review 'tc18' 'APPROVE' 'bank-a' 'decision-1'
        # A client retry with the same key gets the original result, not a second-decision refusal.
        $retried = Send-Review 'tc18' 'APPROVE' 'bank-a' 'decision-1'
        $p = Wait-Card 'tc18'
        Result ($parked.pendingReview.kind -eq 'CONFIRMATION_TIMEOUT' -and $wrong.code -eq 409 -and (Get-Code $wrong) -eq 'ACTION_NOT_ALLOWED' -and
            $review.code -eq 200 -and $retried.code -eq 200 -and $p.status -eq 'ACTIVE') `
            "wrongAction=$($wrong.code)/$(Get-Code $wrong) approve=$($review.code) retriedApprove=$($retried.code) final=$($p.status)"
    }

    Test-Scenario 'tc19' 'Duplicate callback is ignored' {
        Reset-Mocks
        Set-Callbacks 'bank-a' 'DUPLICATE'
        # Keep the workflow running so the second callback reaches it rather than finding it finished.
        Set-Failure 'bank-a' 'notification/send' @{ mode = 'LATENCY'; times = -1; latencyMs = 2500 }
        New-CardRequest 'tc19' | Out-Null
        $p = Wait-Card 'tc19'
        Start-Sleep -Seconds 1
        $signals = Get-SignalCount 'card-bank-a-tc19'
        $cards = @(Get-Cards 'bank-a')
        Result ($p.status -eq 'ACTIVE' -and $signals -eq 2 -and $cards.Count -eq 1) "status=$($p.status) signals=$signals cards=$($cards.Count)"
    }

    Test-Scenario 'tc20' 'Decisions on a finished request are refused' {
        $review = Send-Review 'tc01' 'APPROVE'
        $cancel = Send-Cancel 'tc01'
        Result ($review.code -eq 409 -and $cancel.code -eq 409) "review=$($review.code)/$(Get-Code $review) cancel=$($cancel.code)/$(Get-Code $cancel)"
    }

    Test-Scenario 'tc21' 'Cancel while waiting for the callback compensates' {
        Reset-Mocks
        Set-Callbacks 'bank-a' 'NEVER'
        New-CardRequest 'tc21' | Out-Null
        Wait-Progress 'tc21' { param($p) (Get-StepState $p 'awaitConfirmation') -eq 'WAITING' } | Out-Null
        $cancel = Send-Cancel 'tc21'
        $p = Wait-Card 'tc21'
        $cards = @(Get-Cards 'bank-a')
        Result ($cancel.code -eq 200 -and $p.status -eq 'CANCELLED' -and $p.reasonCode -eq 'CANCELLED_BY_REQUEST' -and
            $cards.Count -eq 1 -and $cards[0].status -eq 'CANCELLED') "cancel=$($cancel.code) final=$($p.status)/$($p.reasonCode) card=$($cards[0].status)"
    }

    Test-Scenario 'tc22' 'Cancel after activation is refused' {
        Reset-Mocks
        Set-Failure 'bank-a' 'notification/send' @{ mode = 'LATENCY'; times = -1; latencyMs = 2500 }
        New-CardRequest 'tc22' | Out-Null
        Wait-Progress 'tc22' { param($p) $p.status -eq 'ACTIVE' } | Out-Null
        $cancel = Send-Cancel 'tc22'
        $p = Wait-Card 'tc22'
        Result ($cancel.code -eq 409 -and (Get-Code $cancel) -eq 'CANNOT_CANCEL' -and $p.status -eq 'ACTIVE') "cancel=$($cancel.code)/$(Get-Code $cancel) final=$($p.status)"
    }

    Test-Scenario 'tc23' 'Reissue a stolen card: block, issue replacement, link' {
        Reset-Mocks
        New-CardRequest 'tc23' | Out-Null
        $old = Wait-Card 'tc23'
        $oldId = $old.card.cardId
        $body = @{ reason = 'STOLEN'; productCode = 'VIRTUAL_STANDARD'; customerRef = 'customer-1'; accountRef = 'account-1'; spendLimitMinor = 50000 }
        $post = Invoke-Http POST "$api/v1/cards/$oldId/reissue" @{ 'X-Tenant-Id' = 'bank-a'; 'Idempotency-Key' = 'tc23r' } $body
        $r = Wait-Progress 'tc23r' { param($p) $p.finished -eq $true } 'bank-a' 'reissues'
        $cards = @(Get-Cards 'bank-a')
        $oldCard = $cards | Where-Object { $_.cardId -eq $oldId }
        $newCard = $cards | Where-Object { $_.cardId -eq $r.card.cardId }
        $order = (Get-ActivityOrder 'reissue-bank-a-tc23r') -join ','
        Result ($post.code -eq 201 -and $r.status -eq 'ACTIVE' -and $oldCard.status -eq 'BLOCKED' -and $newCard.status -eq 'ACTIVE' -and
            $oldCard.replacedBy -eq $r.card.cardId -and $order -eq 'BlockCard,LinkReplacement') `
            "post=$($post.code) reissue=$($r.status) old=$($oldCard.status) new=$($newCard.status) linked=$($oldCard.replacedBy -eq $r.card.cardId) order=$order"
    }

    Test-Scenario 'tc24' 'Reissue of an unknown card is rejected' {
        $body = @{ reason = 'LOST'; productCode = 'VIRTUAL_STANDARD'; customerRef = 'customer-1'; accountRef = 'account-1'; spendLimitMinor = 50000 }
        Invoke-Http POST "$api/v1/cards/card_NOSUCHCARD/reissue" @{ 'X-Tenant-Id' = 'bank-a'; 'Idempotency-Key' = 'tc24r' } $body | Out-Null
        $r = Wait-Progress 'tc24r' { param($p) $p.finished -eq $true } 'bank-a' 'reissues'
        Result ($r.status -eq 'REJECTED' -and $r.reasonCode -eq 'CARD_NOT_FOUND') "status=$($r.status) reason=$($r.reasonCode)"
    }

    Test-Scenario 'tc25' 'Reconciliation finds a card the platform never issued' {
        Reset-Mocks
        New-CardRequest 'tc25' | Out-Null
        $issued = Wait-Card 'tc25'
        $orphan = New-Orphan 'bank-a'
        $post = Invoke-Http POST "$api/v1/reconciliations" @{ 'X-Tenant-Id' = 'bank-a'; 'Idempotency-Key' = 'r25' } @{ pageSize = 50 }
        $run = Wait-Reconciliation 'r25'
        $found = $run.report.discrepancies | Where-Object { $_.cardId -eq $orphan }
        $issuedFlagged = @($run.report.discrepancies | Where-Object { $_.cardId -eq $issued.card.cardId }).Count
        Result ($post.code -eq 201 -and $run.status -eq 'COMPLETED' -and $found.type -eq 'UNKNOWN_TO_PLATFORM' -and $issuedFlagged -eq 0 -and
            $run.report.processorCards -eq 2) "run=$($run.status) orphan=$($found.type) issuedFlagged=$issuedFlagged processorCards=$($run.report.processorCards)"
    }

    Test-Scenario 'tc26' 'Worker dies mid-reconciliation: heartbeat lets the retry resume' {
        Reset-Mocks
        1..6 | ForEach-Object { New-Orphan 'bank-a' | Out-Null }
        Set-Failure 'bank-a' 'processor/listCards' @{ mode = 'LATENCY'; times = -1; latencyMs = 1500 }
        Invoke-Http POST "$api/v1/reconciliations" @{ 'X-Tenant-Id' = 'bank-a'; 'Idempotency-Key' = 'r26' } @{ pageSize = 1 } | Out-Null
        $end = (Get-Date).AddSeconds(30)
        # Wait past the 4-second heartbeat throttle window, so some page progress has reached Temporal.
        do { Start-Sleep -Milliseconds 300; $before = (Get-Counters 'bank-a').calls.'processor/listCards' } while ($before -lt 4 -and (Get-Date) -lt $end)
        Stop-ServiceProcess 'worker'
        Start-Sleep -Seconds 1
        $atKill = (Get-Counters 'bank-a').calls.'processor/listCards'
        Start-Worker 'worker'
        $run = Wait-Reconciliation 'r26'
        $total = (Get-Counters 'bank-a').calls.'processor/listCards'
        $started = @(Get-Events 'reconcile-bank-a-r26' | Where-Object { $_.eventType -match 'ACTIVITY_TASK_STARTED' } |
            ForEach-Object { [int]$_.activityTaskStartedEventAttributes.attempt })
        $attempt = ($started | Measure-Object -Maximum).Maximum
        # Starting over would read all 6 pages again; resuming reads fewer.
        Result ($run.status -eq 'COMPLETED' -and $run.report.processorCards -eq 6 -and $attempt -ge 2 -and ($total - $atKill) -lt 6) `
            "run=$($run.status) processorCards=$($run.report.processorCards) attempt=$attempt pagesBeforeKill=$atKill pagesAfterRestart=$($total - $atKill)"
    }

    Test-Scenario 'tc27' 'Nightly schedule skips a run while one is still going' {
        Reset-Mocks
        1..2 | ForEach-Object { New-Orphan 'bank-a' | Out-Null }
        Set-Failure 'bank-a' 'processor/listCards' @{ mode = 'LATENCY'; times = -1; latencyMs = 2500 }
        $first = Invoke-Http POST "$api/v1/reconciliations/schedule/trigger" @{ 'X-Tenant-Id' = 'bank-a' } $null
        Start-Sleep -Seconds 1
        $second = Invoke-Http POST "$api/v1/reconciliations/schedule/trigger" @{ 'X-Tenant-Id' = 'bank-a' } $null
        Start-Sleep -Seconds 3
        $runs = @(Find-Workflows "TemporalScheduledById='recon-bank-a'")
        Result ($first.code -eq 202 -and $second.code -eq 202 -and $runs.Count -eq 1) "triggers=$($first.code),$($second.code) scheduledRuns=$($runs.Count)"
    }

    # =====================================================================
    # Edge cases
    # =====================================================================

    Test-Scenario 'tc28' 'Card number in a cancel reason is refused' {
        $r = Send-Cancel 'tc21' 'bank-a' 'card 4111 1111 1111 1111 lost'
        Result ($r.code -eq 400 -and (Get-Code $r) -eq 'INVALID_REQUEST') "http=$($r.code)/$(Get-Code $r)"
    }

    Test-Scenario 'tc29' 'Reusing a finished reconciliation key does not start another run' {
        $again = Invoke-Http POST "$api/v1/reconciliations" @{ 'X-Tenant-Id' = 'bank-a'; 'Idempotency-Key' = 'r25' } @{ pageSize = 50 }
        Start-Sleep -Seconds 1
        $runs = @(Find-Workflows "WorkflowId='reconcile-bank-a-r25'")
        Result ($again.code -eq 200 -and $runs.Count -eq 1) "http=$($again.code) runs=$($runs.Count)"
    }

    Test-Scenario 'tc30' 'A missing schedule is recreated when triggered' {
        Reset-Mocks
        & $temporal schedule delete -s recon-bank-a --address $address 2>$null | Out-Null
        & $temporal schedule describe -s recon-bank-a --address $address 2>$null | Out-Null
        $goneBefore = $LASTEXITCODE -ne 0
        $trigger = Invoke-Http POST "$api/v1/reconciliations/schedule/trigger" @{ 'X-Tenant-Id' = 'bank-a' } $null
        & $temporal schedule describe -s recon-bank-a --address $address 2>$null | Out-Null
        $existsAfter = $LASTEXITCODE -eq 0
        Result ($goneBefore -and $trigger.code -eq 202 -and $existsAfter) "deleted=$goneBefore trigger=$($trigger.code) recreated=$existsAfter"
    }

    Test-Scenario 'tc31' 'Callback sent while the API is down is retried and still arrives' {
        Reset-Mocks
        Set-Callbacks 'bank-a' 'DELAY' 2000
        New-CardRequest 'tc31' | Out-Null
        Stop-ServiceProcess 'api'
        Start-Sleep -Seconds 1
        Start-Service 'api' $java @('-jar', $jars.api) | Out-Null
        $up = Wait-Port $ApiPort 90
        $p = Wait-Card 'tc31'
        $attempts = @(Select-String -Path "$logs\mock.out" -Pattern 'for tc31 (answered|failed)').Count
        $signals = Get-SignalCount 'card-bank-a-tc31'
        Result ($up -and $p.status -eq 'ACTIVE' -and $signals -eq 1 -and $attempts -ge 2) `
            "apiBack=$up final=$($p.status) deliveryAttempts=$attempts signals=$signals"
    }

    $failed = @($results | Where-Object Result -eq 'FAIL').Count
    $exitCode = if ($failed -eq 0) { 0 } else { 1 }
} catch {
    Write-Host ''
    Write-Host "Run aborted: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 2
} finally {
    foreach ($name in @($procs.Keys)) { Stop-ServiceProcess $name }
}

Write-Host ''
$passed = @($results | Where-Object Result -eq 'PASS').Count
$failedCount = @($results | Where-Object Result -eq 'FAIL').Count
$colour = if ($exitCode -eq 0) { 'Green' } else { 'Red' }
Write-Host "Passed: $passed  Failed: $failedCount" -ForegroundColor $colour
if ($exitCode -ne 0) { Write-Host "Service logs: $logs" }
exit $exitCode
