#!/usr/bin/env sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
command -v mvn >/dev/null 2>&1 || { echo "Apache Maven 3.9 or later is required and must be available on PATH." >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "JDK 21 or later is required." >&2; exit 1; }
cd "$APP_DIR"
echo "Building DB-Diff Studio with Maven. The first run may take longer while dependencies download..."
mvn package
echo "Build complete. Starting DB-Diff Studio..."
exec java -jar "$APP_DIR/target/db-diff-studio-0.2.0.jar"
