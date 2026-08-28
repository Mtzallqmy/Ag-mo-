@echo off
setlocal enabledelayedexpansion
set GRADLE_VERSION=8.11.1
set GRADLE_SHA256=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
if "%GRADLE_USER_HOME%"=="" (set BASE=%USERPROFILE%\.gradle\al-agent-bootstrap) else (set BASE=%GRADLE_USER_HOME%\al-agent-bootstrap)
set ZIP=%BASE%\gradle-%GRADLE_VERSION%-bin.zip
set DIST=%BASE%\gradle-%GRADLE_VERSION%
if exist "%DIST%\bin\gradle.bat" goto run
if not exist "%BASE%" mkdir "%BASE%"
if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
for /f "tokens=1" %%H in ('certutil -hashfile "%ZIP%" SHA256 ^| findstr /R /V "hash CertUtil"') do set ACTUAL=%%H
if /I not "!ACTUAL!"=="%GRADLE_SHA256%" (echo Gradle checksum verification failed.& del /Q "%ZIP%" & exit /b 3)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%BASE%' -Force"
:run
call "%DIST%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
