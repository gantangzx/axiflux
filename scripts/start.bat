@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================================
rem Tianshu unified launcher (Windows).
rem
rem Usage:
rem   scripts\start.bat [local^|dev^|test^|prod] [-f]
rem     profile defaults to local.
rem     -f  run in foreground (default: detached minimized window).
rem
rem Env files (gitignored), loaded automatically when present:
rem   env\env.local.bat   env\env.dev.bat   env\env.test.bat   env\env.prod.bat
rem Copy the corresponding template in env\ and fill in secrets.
rem ============================================================================

set "PROFILE=local"
set "FOREGROUND=0"

rem Capture the project root BEFORE the shift loop: after `shift`, %0 no longer
rem refers to this script and %~dp0 silently resolves one directory too high.
for %%I in ("%~dp0..") do set "ROOT=%%~fI"

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="-f" (set "FOREGROUND=1") else (set "PROFILE=%~1")
shift
goto parse
:parsed

pushd "%ROOT%"

rem Validate profile.
set "VALID=0"
for %%P in (local dev test prod) do if /i "%PROFILE%"=="%%P" set "VALID=1"
if "%VALID%"=="0" (
  echo [ERROR] unknown profile '%PROFILE%'; expected local, dev, test or prod.
  popd
  exit /b 2
)

rem Load env file. local tolerates absence (boots with base defaults); others require it.
set "ENVFILE=env\env.%PROFILE%.bat"
if exist "%ENVFILE%" (
  call "%ENVFILE%"
) else (
  if /i not "%PROFILE%"=="local" (
    echo [ERROR] %ENVFILE% not found. Copy env\env.%PROFILE%.bat.example and fill it.
    popd
    exit /b 1
  )
)

rem JDK. An inherited JAVA_HOME may point at an older JDK that cannot run the
rem Java 25 compiled jar; fall back to the bundled JDK when it is not usable.
set "DEFAULT_JAVA_HOME=D:\software\Java\jdk-25\jdk-25.0.2"
if not defined JAVA_HOME set "JAVA_HOME=%DEFAULT_JAVA_HOME%"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=%DEFAULT_JAVA_HOME%"
set "APP_JAR=tianshu-app\target\tianshu-app-0.1.0-SNAPSHOT.jar"
if not exist "%APP_JAR%" (
  echo [ERROR] jar not found: %APP_JAR%
  echo Build first:  mvn clean install -DskipTests
  popd
  exit /b 1
)

set "SPRING_PROFILES_ACTIVE=%PROFILE%"
set "LOG=logs\boot-%PROFILE%.log"
if not exist logs mkdir logs

if "%FOREGROUND%"=="1" (
  echo starting '%PROFILE%' in foreground, logs -^> console
  "%JAVA_HOME%\bin\java.exe" -jar "%APP_JAR%"
) else (
  start "tianshu-%PROFILE%" /min cmd /c ""%JAVA_HOME%\bin\java.exe" -jar "%APP_JAR%" > %LOG% 2>&1"
  echo launched profile '%PROFILE%' detached; logs -^> %LOG%
)

popd
endlocal
