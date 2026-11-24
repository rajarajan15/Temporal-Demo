@echo off
cd /d "%~dp0..\.."
for /d %%j in (.tools\jdk\*) do set "JAVA_HOME=%CD%\%%j"
for /d %%m in (.tools\maven\*) do set "MVN=%%m\bin\mvn.cmd"
call "%MVN%" -B clean verify
