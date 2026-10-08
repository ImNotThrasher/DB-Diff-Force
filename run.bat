@echo off
setlocal
set "APP_DIR=%~dp0"
set "APP_NAME=DB-Diff-Force"
where mvn >nul 2>nul || (echo Apache Maven 3.9 or later is required and must be available on PATH.& exit /b 1)
where javaw >nul 2>nul || (echo JDK 21 or later is required.& exit /b 1)
set "MAVEN_OPTS=%MAVEN_OPTS% -Djavax.net.ssl.trustStoreType=Windows-ROOT"
pushd "%APP_DIR%"
echo Compilando %APP_NAME% con Maven. La primera ejecucion puede tardar mas mientras se descargan las dependencias...
call mvn package
if errorlevel 1 (popd& exit /b 1)
echo Compilacion lista. Abriendo %APP_NAME% en una ventana independiente...
start "%APP_NAME%" javaw -jar "%APP_DIR%target\db-diff-force-0.2.0.jar"
set "EXIT_CODE=%ERRORLEVEL%"
popd
endlocal & exit /b %EXIT_CODE%
