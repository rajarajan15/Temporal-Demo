<#
.SYNOPSIS
  Stops the Temporal dev server, mocks, worker and API started by start-all.cmd or the start-*.cmd scripts.

.DESCRIPTION
  Matches processes by command line, so it only stops this project's dev services. The scenario
  runner's services use absolute jar paths and other ports, so they are left alone.
  Use -DryRun to list what would be stopped without stopping anything.
#>
param([switch]$DryRun)

$ErrorActionPreference = 'Continue'

# Each pattern matches one service exactly as the start-*.cmd scripts launch it, with paths
# relative to the repo root. Stopped in this order: API, worker, mocks, then Temporal.
$services = [ordered]@{
    'API'      = '-jar\s+api-app\\target\\api-app-[^\s]*-exec\.jar'
    'Worker'   = '-jar\s+worker-app\\target\\worker-app-[^\s]*-exec\.jar'
    'Mocks'    = '-jar\s+mock-services\\target\\mock-services-[^\s]*-exec\.jar'
    'Temporal' = 'temporal\.exe"?\s+server\s+start-dev\s+--db-filename\s+\.tools\\temporal-dev\.db'
}
# The console windows opened by start-all.cmd, which stay open after their service stops.
$windowPattern = 'cmd(\.exe)?"?\s+/k\s+"?[^"]*\\start-(temporal|mocks|worker|api)\.cmd'

$processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='temporal.exe' OR Name='cmd.exe'")
$found = 0
foreach ($name in $services.Keys) {
    foreach ($p in @($processes | Where-Object { $_.CommandLine -match $services[$name] })) {
        $found++
        if ($DryRun) {
            Write-Host "Would stop $name (process $($p.ProcessId))"
        } else {
            Stop-Process -Id $p.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue
            Write-Host "Stopped $name (process $($p.ProcessId))"
        }
    }
}

foreach ($w in @($processes | Where-Object { $_.CommandLine -match $windowPattern })) {
    if ($DryRun) {
        Write-Host "Would close window (process $($w.ProcessId))"
    } else {
        Stop-Process -Id $w.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue
    }
}

if ($found -eq 0) {
    Write-Host 'No services were running.'
}
if ($DryRun) {
    exit 0
}

# Wait for the ports to be released, so start-all.cmd can start again straight away.
$ports = 7233, 8233, 8080, 8091
$end = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $end) {
    $busy = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })
    if ($busy.Count -eq 0) {
        Write-Host 'All services stopped.'
        exit 0
    }
    Start-Sleep -Milliseconds 500
}
Write-Host "Ports still in use: $(($busy.LocalPort | Sort-Object -Unique) -join ', '). Another program may be using them." -ForegroundColor Yellow
exit 1
