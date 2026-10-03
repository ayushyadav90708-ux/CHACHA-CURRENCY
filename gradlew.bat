@echo off
setlocal
set GRADLE_VERSION=8.10.2
set BASE=%USERPROFILE%\.gradle\kodari-wrapper\gradle-%GRADLE_VERSION%
set EXEC=%BASE%\gradle-%GRADLE_VERSION%\bin\gradle.bat
if not exist "%EXEC%" (
  if not exist "%BASE%" mkdir "%BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip='%BASE%\gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -Force $zip '%BASE%'; Remove-Item $zip"
  if errorlevel 1 exit /b 1
)
call "%EXEC%" %*
endlocal