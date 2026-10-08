#!/usr/bin/env sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_NAME="DB-Diff-Force"
command -v mvn >/dev/null 2>&1 || { echo "Apache Maven 3.9 or later is required and must be available on PATH." >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "JDK 21 or later is required." >&2; exit 1; }
cd "$APP_DIR"
echo "Compilando $APP_NAME con Maven. La primera ejecucion puede tardar mas mientras se descargan las dependencias..."
mvn package
echo "Compilacion lista. Iniciando $APP_NAME..."
exec java -jar "$APP_DIR/target/db-diff-force-0.2.0.jar"
