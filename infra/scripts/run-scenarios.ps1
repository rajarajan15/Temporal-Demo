<#
.SYNOPSIS
  Runs the Milestone 2 scenarios against real Temporal, mock, worker and API processes.

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

function Invoke-Http([string]$method, [string]$url, [hashtable]$headers, $body) {
    try {
        $request = @{ UseBasicParsing = $true; Method = $method; Uri = $url; TimeoutSec = 30 }
        if ($headers) { $request.Headers = $headers }
        if ($null -ne $body) { $request.ContentType = 'application/json'; $request.Body = ($body | ConvertTo-Json -Compress) }
        $response = Invoke-WebRequest @request
        return @{ code = [int]$response.StatusCode; body = $response.Content }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return @{ code = $code; body = '' }
    }
}

# Windows PowerShell returns a JSON array as one object; this unrolls it.
function ConvertTo-List([string]$json) {
    if (-not $json) { return @() }
    return @(($json | ConvertFrom-Json) | ForEach-Object { $_ })
}

function Reset-Mocks { Invoke-Http POST "$mock/mock-admin/reset" $null $null | Out-Null }
function Set-Failure([string]$tenant, [string]$target, $rule) { Invoke-Http PUT "$mock/mock-admin/failures/$tenant/$target" $null $rule | Out-Null }
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

function Get-Progress([string]$key, [string]$tenant = 'bank-a') {
    return Invoke-Http GET "$api/v1/card-requests/$key" @{ 'X-Tenant-Id' = $tenant } $null
}

# Waits until the card reaches a business end state and no step is still running.
function Wait-Card([string]$key, [string]$tenant = 'bank-a') {
    $end = (Get-Date).AddSeconds($TimeoutSeconds)
    $last = $null
    while ((Get-Date) -lt $end) {
        $r = Get-Progress $key $tenant
        if ($r.code -eq 200) {
            $last = $r.body | ConvertFrom-Json
            $running = @($last.steps | Where-Object { $_.state -in 'RUNNING', 'COMPENSATING' }).Count
            if ($last.status -in 'ACTIVE', 'REJECTED', 'CANCELLED', 'UNDER_REVIEW' -and $running -eq 0) { return $last }
        }
        Start-Sleep -Milliseconds 500
    }
    return $last
}

function Get-StepState($progress, [string]$name) {
    return ($progress.steps | Where-Object { $_.name -eq $name } | Select-Object -First 1).state
}

function Get-History([string]$key, [string]$tenant = 'bank-a') {
    return (& $temporal workflow show -w "card-$tenant-$key" --address $address -o json 2>$null) -join "`n"
}

function Get-ActivityOrder([string]$key, [string]$tenant = 'bank-a') {
    $history = Get-History $key $tenant | ConvertFrom-Json
    return @($history.events |
        Where-Object { $_.eventType -match 'ACTIVITY_TASK_SCHEDULED' } |
        ForEach-Object { $_.activityTaskScheduledEventAttributes.activityType.name })
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
$env:API_PORT = "$ApiPort"

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
    Start-Service 'worker' $java @('-jar', $jars.worker) | Out-Null
    Start-Service 'api' $java @('-jar', $jars.api) | Out-Null
    if (-not (Wait-Port $MockPort 90)) { throw 'Mock services did not start.' }
    if (-not (Wait-Port $ApiPort 90)) { throw 'API did not start.' }
    Start-Sleep -Seconds 5
    if ($procs['worker'].HasExited) { throw 'Worker exited during startup. See worker.out.' }
    Write-Host ''

    # ---------- scenarios ----------

    Test-Scenario 'tc01' 'Happy path' {
        Reset-Mocks
        $post = New-CardRequest 'tc01'
        $p = Wait-Card 'tc01'
        $cards = @(Get-Cards 'bank-a')
        $done = @($p.steps | Where-Object { $_.state -eq 'DONE' }).Count
        $order = (Get-ActivityOrder 'tc01') -join ','
        $expected = 'ValidateRequest,RunKyc,CreateCard,SetLimits,ActivateCard,SendNotification'
        Result ($post.code -eq 201 -and $p.status -eq 'ACTIVE' -and $done -eq 6 -and $p.card.cardId -and
            $cards.Count -eq 1 -and $cards[0].status -eq 'ACTIVE' -and $cards[0].limitMinor -eq 50000 -and $order -eq $expected) `
            "http=$($post.code) status=$($p.status) doneSteps=$done cards=$($cards.Count) order=$order"
    }

    Test-Scenario 'tc01' 'History holds no card numbers' {
        $history = Get-History 'tc01'
        $decoded = ([regex]::Matches($history, '"data":\s*"([A-Za-z0-9+/=]+)"') | ForEach-Object {
                try { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_.Groups[1].Value)) } catch { '' }
            }) -join ' '
        $leak = [regex]::Match("$history $decoded", '(?<!\d)\d{13,19}(?!\d)')
        Result ($decoded.Length -gt 0 -and -not $leak.Success) "payloadChars=$($decoded.Length) match=$($leak.Value)"
    }

    Test-Scenario 'tc01' 'Search attributes are queryable' {
        $query = "TenantId='bank-a' AND CardType='VIRTUAL' AND CardStatus='ACTIVE' AND RequestId='tc01'"
        $found = @(& $temporal workflow list --address $address --query $query -o json 2>$null | ConvertFrom-Json | ForEach-Object { $_ })
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

    Test-Scenario 'tc08' 'KYC needs review' {
        New-CardRequest 'tc08' @{ customerRef = 'review-1' } | Out-Null
        $p = Wait-Card 'tc08'
        Result ($p.status -eq 'UNDER_REVIEW' -and $p.reasonCode -eq 'KYC_NEEDS_REVIEW') "status=$($p.status) reason=$($p.reasonCode)"
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
        $tail = ((Get-ActivityOrder 'tc11') | Select-Object -Last 4) -join ','
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

    Test-Scenario 'tc13' 'Bank A processor offline: bank B unaffected (tc14)' {
        Reset-Mocks
        Set-Failure 'bank-a' 'processor/createCard' @{ mode = 'OFFLINE'; times = -1 }
        New-CardRequest 'tc13' @{} 'bank-a' | Out-Null
        $startB = Get-Date
        New-CardRequest 'tc14' @{} 'bank-b' | Out-Null
        $b = Wait-Card 'tc14' 'bank-b'
        $bSeconds = [int]((Get-Date) - $startB).TotalSeconds
        $a = Wait-Card 'tc13' 'bank-a'
        Result ($b.status -eq 'ACTIVE' -and $bSeconds -le 15 -and $a.status -eq 'CANCELLED' -and $a.reasonCode -eq 'RETRIES_EXHAUSTED') `
            "bank-b=$($b.status) in $bSeconds s, bank-a=$($a.status)/$($a.reasonCode)"
    }

    Test-Scenario 'tc15' 'Worker crash, then recovery' {
        Reset-Mocks
        Stop-ServiceProcess 'worker'
        Start-Sleep -Seconds 1
        $post = New-CardRequest 'tc15'
        $whileDown = Get-Progress 'tc15'
        Start-Service 'worker' $java @('-jar', $jars.worker) | Out-Null
        $p = Wait-Card 'tc15'
        $cards = @(Get-Cards 'bank-a')
        Result ($post.code -eq 201 -and $whileDown.code -eq 503 -and $p.status -eq 'ACTIVE' -and $cards.Count -eq 1) `
            "post=$($post.code) whileDown=$($whileDown.code) after=$($p.status) cards=$($cards.Count)"
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
