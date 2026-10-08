@echo off
setlocal
set "APP_DIR=%~dp0"
set "DRIVER_DIR=%LOCALAPPDATA%\DB-Diff-Studio\drivers"
set "DRIVER_JAR=%DRIVER_DIR%\mariadb-java-client-3.5.3.jar"
if not exist "%DRIVER_JAR%" (
  if not exist "%DRIVER_DIR%" mkdir "%DRIVER_DIR%"
  echo Downloading MariaDB JDBC driver...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.3/mariadb-java-client-3.5.3.jar' -OutFile '%DRIVER_JAR%'"
  if errorlevel 1 exit /b 1
)
if not exist "%APP_DIR%out" mkdir "%APP_DIR%out"
where javac >nul 2>nul || (echo JDK 21 or later is required.& exit /b 1)
javac -encoding UTF-8 -cp "%DRIVER_JAR%" -d "%APP_DIR%out" "%APP_DIR%src\main\java\studio\dbdiff\"*.java
if errorlevel 1 exit /b 1
java -cp "%APP_DIR%out;%DRIVER_JAR%" studio.dbdiff.Main
endlocal
