@echo off
setlocal
set GRADLE_VERSION=8.9
set DIST_DIR=%USERPROFILE%\.gradle\git2app\gradle-%GRADLE_VERSION%
if not exist "%DIST_DIR%\bin\gradle.bat" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path $env:TEMP 'gradle-%GRADLE_VERSION%.zip'; $tmp=Join-Path $env:TEMP ('gradle-' + [guid]::NewGuid()); Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath $tmp -Force; New-Item -ItemType Directory -Force -Path (Split-Path '%DIST_DIR%') | Out-Null; if (Test-Path '%DIST_DIR%') { Remove-Item '%DIST_DIR%' -Recurse -Force }; Move-Item (Join-Path $tmp 'gradle-%GRADLE_VERSION%') '%DIST_DIR%'"
)
"%DIST_DIR%\bin\gradle.bat" %*
