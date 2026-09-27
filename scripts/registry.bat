@echo off
setlocal EnableExtensions
rem ============================================================================
rem Tianshu Skill Registry launcher (Windows).
rem
rem Usage:
rem   scripts\registry.bat [-f]
rem     default: detached minimized window, logs to logs\registry.log
rem     -f:       foreground
rem
rem Env file (gitignored), loaded automatically when present:
rem   env\env.registry.bat   (copy env\env.registry.bat.example and fill it)
rem
rem The registry refuses to start unless REGISTRY_PUBLISH_TOKEN is set to a
rem strong value (not the built-in "change-me").
rem ============================================================================

set "FOREGROUND=0"
if /i "%~1"=="-f" set "FOREGROUND=1"

set "ROOT=%~dp0.."
pushd "%ROOT%"

if exist "env\env.registry.bat" (
  call "env\env.registry.bat"
) else (
  echo [ERROR] env\env.registry.bat not found. Copy env\env.registry.bat.example and fill it.
  popd
  exit /b 1
)

if not defined JAVA_HOME set "JAVA_HOME=D:\software\Java\jdk-25\jdk-25.0.2"
set "APP_JAR=tianshu-registry\target\tianshu-registry-0.1.0-SNAPSHOT.jar"
if not exist "%APP_JAR%" (
  echo [ERROR] jar not found: %APP_JAR%
  echo Build first:  mvn clean install -DskipTests -pl tianshu-registry -am
  popd
  exit /b 1
)

if not defined REGISTRY_PORT set "REGISTRY_PORT=8090"
if not exist logs mkdir logs
set "LOG=logs\registry.log"

if "%FOREGROUND%"=="1" (
  echo starting registry on port %REGISTRY_PORT% in foreground
  "%JAVA_HOME%\bin\java.exe" -jar "%APP_JAR%"
) else (
  start "tianshu-registry" /min cmd /c ""%JAVA_HOME%\bin\java.exe" -jar "%APP_JAR%" > %LOG% 2>&1"
  echo launched registry on port %REGISTRY_PORT% detached; logs -^> %LOG%
)

popd
endlocal
