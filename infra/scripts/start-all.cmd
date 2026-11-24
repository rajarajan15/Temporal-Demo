@echo off
rem Starts Temporal, mocks, worker and API, each in its own window, in the right order.
setlocal
cd /d "%~dp0..\.."

netstat -ano | findstr /R /C:":7233 .*LISTENING" /C:":8080 .*LISTENING" /C:":8091 .*LISTENING" >nul
if not errorlevel 1 (
    echo Some services are already running.
    echo Use infra\scripts\restart-all.cmd, or stop them first with infra\scripts\stop-all.cmd.
    exit /b 1
)

for %%j in (mock-services worker-app api-app) do (
    if not exist "%%j\target\%%j-0.1.0-SNAPSHOT-exec.jar" (
        echo %%j has not been built. Run infra\scripts\build.cmd first.
        exit /b 1
    )
)

echo Starting Temporal ...
start "Card Issuance - Temporal" cmd /k "%~dp0start-temporal.cmd"
call :wait_for_port 7233 60 Temporal || exit /b 1

echo Starting mocks, worker and API ...
start "Card Issuance - Mocks" cmd /k "%~dp0start-mocks.cmd"
start "Card Issuance - Worker" cmd /k "%~dp0start-worker.cmd"
start "Card Issuance - API" cmd /k "%~dp0start-api.cmd"
call :wait_for_port 8091 90 Mocks || exit /b 1
call :wait_for_port 8080 90 API || exit /b 1

echo.
echo All services are up. Each runs in its own window.
echo   Temporal Web UI   http://localhost:8233
echo   API               http://localhost:8080
echo   Mocks and admin   http://localhost:8091
echo The worker has no port. Check its window for errors if requests stay at REQUESTED.
exit /b 0

rem Waits until a port is listening. Arguments: port, seconds, name.
:wait_for_port
set /a waited=0
:wait_loop
netstat -ano | findstr /R /C:":%1 .*LISTENING" >nul
if not errorlevel 1 (
    echo   %3 is up on port %1
    exit /b 0
)
if %waited% geq %2 (
    echo   %3 did not start within %2 seconds. Check its window.
    exit /b 1
)
rem One-second pause that also works when input is redirected.
ping -n 2 127.0.0.1 >nul
set /a waited+=1
goto wait_loop
