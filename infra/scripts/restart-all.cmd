@echo off
rem Stops all services, rebuilds with tests, and starts them again with the new code.
rem The services must be stopped before building, because Windows locks their jars while they run.
setlocal
cd /d "%~dp0..\.."

call "%~dp0stop-all.cmd"
if errorlevel 1 (
    echo Could not stop every service. Not rebuilding.
    exit /b 1
)

call "%~dp0build.cmd"
if errorlevel 1 (
    echo Build failed. Services were not restarted.
    exit /b 1
)

call "%~dp0start-all.cmd"
exit /b %ERRORLEVEL%
