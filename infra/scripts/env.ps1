# Dot-source from the repo root to use the local toolchain: . .\infra\scripts\env.ps1
$tools = Join-Path (Resolve-Path "$PSScriptRoot\..\..") '.tools'
$env:JAVA_HOME = (Get-ChildItem "$tools\jdk" -Directory | Select-Object -First 1).FullName
$maven = (Get-ChildItem "$tools\maven" -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$maven\bin;$tools\temporal;$env:Path"
