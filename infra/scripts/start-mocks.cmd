@echo off
cd /d "%~dp0..\.."
for /d %%j in (.tools\jdk\*) do set "JAVA=%%j\bin\java.exe"
"%JAVA%" -jar mock-services\target\mock-services-0.1.0-SNAPSHOT-exec.jar
