@echo off
rem Stops Temporal, mocks, worker and API. Add -DryRun to only list what would be stopped.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1" %*
exit /b %ERRORLEVEL%
