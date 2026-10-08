@echo off
setlocal
set "APP_DIR=%~dp0"
where mvn >nul 2>nul || (echo Apache Maven 3.9 or later is required and must be available on PATH.& exit /b 1)
where javaw >nul 2>nul || (echo JDK 21 or later is required.& exit /b 1)
set "MAVEN_OPTS=%MAVEN_OPTS% -Djavax.net.ssl.trustStoreType=Windows-ROOT"
pushd "%APP_DIR%"
mvn -q package
if errorlevel 1 (popd& exit /b 1)
echo Starting DB-Diff Studio...
start "DB-Diff Studio" javaw -jar "%APP_DIR%target\db-diff-studio-0.2.0.jar"
set "EXIT_CODE=%ERRORLEVEL%"
popd
endlocal & exit /b %EXIT_CODE%
