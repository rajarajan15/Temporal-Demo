@echo off
rem Runs every Milestone 2 scenario on separate ports. Build first with infra\scripts\build.cmd.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-scenarios.ps1" %*
exit /b %ERRORLEVEL%
