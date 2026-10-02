#!/bin/sh
cd "$(dirname "$0")" || exit 1
if [ -f .env ]; then
    set -a
    . ./.env || exit 1
    set +a
fi
exec java --add-opens java.base/java.lang=ALL-UNNAMED -jar target/quarkus-app/quarkus-run.jar "$@"