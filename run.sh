#!/usr/bin/env sh
set -eu

APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if ! command -v java >/dev/null 2>&1; then
  echo "Se requiere Java 21 o posterior y debe estar disponible en PATH." >&2
  exit 1
fi

set -- "$APP_DIR"/db-diff-force-*.jar
if [ -f "$1" ]; then
  APP_JAR=$1
else
  if [ ! -f "$APP_DIR/pom.xml" ]; then
    echo "No se encontro el JAR de la aplicacion junto al lanzador." >&2
    exit 1
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    echo "Se requiere Apache Maven 3.9 o posterior para compilar el codigo fuente." >&2
    exit 1
  fi

  cd "$APP_DIR"
  mvn package
  set -- "$APP_DIR"/target/db-diff-force-*.jar
  if [ ! -f "$1" ]; then
    echo "No se encontro el JAR de la aplicacion en target/." >&2
    exit 1
  fi
  APP_JAR=$1
fi

exec java -jar "$APP_JAR"
