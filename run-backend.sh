#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ ! -f backend/build/libs/etiket-backend-0.6.0.jar ]; then
  echo 'Build the backend first: ./gradlew -PbackendOnly :backend:bootJar' >&2
  exit 1
fi
if [ -n "${JAVA_HOME:-}" ]; then
  exec "$JAVA_HOME/bin/java" -jar backend/build/libs/etiket-backend-0.6.0.jar
else
  exec java -jar backend/build/libs/etiket-backend-0.6.0.jar
fi
