#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ -n "${JAVA_HOME:-}" ]; then
  exec "$JAVA_HOME/bin/java" -jar dist/etiket-backend-0.2.0.jar
else
  exec java -jar dist/etiket-backend-0.2.0.jar
fi
