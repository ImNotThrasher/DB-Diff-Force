#!/usr/bin/env sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DRIVER_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/db-diff-studio/drivers"
DRIVER_JAR="$DRIVER_DIR/mariadb-java-client-3.5.3.jar"
if [ ! -f "$DRIVER_JAR" ]; then
  mkdir -p "$DRIVER_DIR"
  echo "Downloading MariaDB JDBC driver..."
  curl -fL 'https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.3/mariadb-java-client-3.5.3.jar' -o "$DRIVER_JAR"
fi
mkdir -p "$APP_DIR/out"
javac -encoding UTF-8 -cp "$DRIVER_JAR" -d "$APP_DIR/out" "$APP_DIR"/src/main/java/studio/dbdiff/*.java
java -cp "$APP_DIR/out:$DRIVER_JAR" studio.dbdiff.Main
