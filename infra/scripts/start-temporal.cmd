@echo off
cd /d "%~dp0..\.."
.tools\temporal\temporal.exe server start-dev --db-filename .tools\temporal-dev.db ^
  --search-attribute TenantId=Keyword ^
  --search-attribute CardId=Keyword ^
  --search-attribute CardType=Keyword ^
  --search-attribute CardStatus=Keyword ^
  --search-attribute RequestId=Keyword
