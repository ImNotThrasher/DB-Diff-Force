@echo off
setlocal
set "APP_DIR=%~dp0"
set "APP_NAME=DB-Diff-Force"

for %%F in ("%APP_DIR%db-diff-force-*.jar") do if exist "%%~fF" set "APP_JAR=%%~fF"
if defined APP_JAR goto launch

if not exist "%APP_DIR%pom.xml" (
    echo No se encontro el JAR de la aplicacion junto al lanzador.
    pause
    exit /b 1
)
where mvn >nul 2>nul || (echo Se requiere Apache Maven 3.9 o posterior para compilar el codigo fuente.& pause& exit /b 1)
set "MAVEN_OPTS=%MAVEN_OPTS% -Djavax.net.ssl.trustStoreType=Windows-ROOT"
pushd "%APP_DIR%"
echo Compilando %APP_NAME% con Maven. La primera compilacion puede tardar mas mientras se descargan las dependencias...
call mvn package
if errorlevel 1 (popd& exit /b 1)
for %%F in ("%APP_DIR%target\db-diff-force-*.jar") do if exist "%%~fF" set "APP_JAR=%%~fF"
popd

:launch
if not defined APP_JAR (
    echo No se encontro el JAR de la aplicacion.
    pause
    exit /b 1
)
start "" "%APP_JAR%"
endlocal
