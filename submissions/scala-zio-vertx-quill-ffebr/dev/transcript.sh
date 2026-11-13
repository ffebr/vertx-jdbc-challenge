#!/usr/bin/env bash
# Run the existing normalized conformance CLI against a fresh seed copy and
# compare its JSONL transcript with the checked-in development golden file.
set -euo pipefail

LOCAL=0
if [[ "${1:-}" == --local ]]; then
  LOCAL=1
  shift
fi
VARIANT="${1:?usage: dev/transcript.sh [--local] jdbc|native [transcript.jsonl]}"
case "$VARIANT" in jdbc|native) ;; *) echo "variant must be jdbc or native" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
DEV="$ROOT/submissions/scala-zio-vertx-quill-ffebr/dev"
APP="$ROOT/submissions/scala-zio-vertx-quill-ffebr/$VARIANT"
DB="scala_zio_${VARIANT}_transcript_$$"
PORT="${PORT:-3000}"
TRANSCRIPT="${2:-/tmp/scala-zio-vertx-quill-ffebr-$VARIANT.jsonl}"
SERVER_LOG="${TRANSCRIPT%.jsonl}.server.log"
TMP="$(mktemp -d /tmp/scala-zio-transcript.XXXXXX)"
PID=""
PSQL=(psql -X -q -v ON_ERROR_STOP=1 -d postgres)
mkdir -p "$(dirname "$TRANSCRIPT")"

if (( LOCAL )); then
  SETUP=/workspace/.setup/challenge
  export PATH="$SETUP/bin:$SETUP/go/bin:$PATH"
  export GOCACHE="$SETUP/go-cache" GOPATH="$SETUP/go-work"
  export PGHOST=127.0.0.1 PGPORT=55438 PGUSER=postgres PGPASSWORD=postgres-dev-only
  bash "$DEV/postgres18.sh"
fi

# Match libpq's ordinary service defaults on a TCP loopback PostgreSQL when
# PGHOST/PGPORT are not supplied; otherwise honor all caller-provided PG* values.
PG_APP_HOST="${PGHOST:-127.0.0.1}"
PG_APP_PORT="${PGPORT:-5432}"
if [[ "$PG_APP_HOST" == /* ]]; then
  echo "The app DATABASE_URL requires a TCP PGHOST; socket-directory PGHOST is not supported." >&2
  exit 2
fi
PG_URL_HOST="$PG_APP_HOST"
if [[ "$PG_URL_HOST" == *:* && "$PG_URL_HOST" != \[*\] ]]; then
  PG_URL_HOST="[$PG_URL_HOST]"
fi
export PGHOST="$PG_APP_HOST" PGPORT="$PG_APP_PORT"
export JWT_SECRET=multicore-server-challenge HOST=127.0.0.1 PORT
BASE="http://$HOST:$PORT"

cleanup() {
  rc=$?
  if [[ -n "$PID" ]]; then
    pkill -P "$PID" 2>/dev/null || true
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  if (( rc != 0 )) && [[ -s "$TMP/server.log" ]]; then
    cp "$TMP/server.log" "$SERVER_LOG"
    echo "Run failed; server log saved to $SERVER_LOG" >&2
  fi
  "${PSQL[@]}" -c "DROP DATABASE IF EXISTS $DB WITH (FORCE)" >/dev/null 2>&1 || true
  rm -rf "$TMP"
  return "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

[[ -d "$APP/target/stage/lib" ]] || { echo "Missing staged app: run $APP/build.sh first." >&2; exit 1; }
"${PSQL[@]}" -c "CREATE DATABASE $DB TEMPLATE conduit_dev STRATEGY FILE_COPY"
export DATABASE_URL="postgres://conduit:conduit@$PG_URL_HOST:$PG_APP_PORT/$DB"

(cd "$ROOT/bench" && go build -o "$TMP/conformance" ./cmd/conformance)
bash "$APP/start.sh" >"$TMP/server.log" 2>&1 &
PID=$!
for _ in $(seq 60); do curl -sf -o /dev/null "$BASE/api/tags" && break; sleep 1; done
curl -sf -o /dev/null "$BASE/api/tags" || { echo "Server did not become ready; log follows:" >&2; cat "$TMP/server.log" >&2; exit 1; }

"$TMP/conformance" -base "$BASE" -secret "$JWT_SECRET" -transcript "$TRANSCRIPT" -q
if cmp -s "$ROOT/bench/cmd/conformance/golden-dev.jsonl" "$TRANSCRIPT"; then
  echo "Transcript matches golden: $TRANSCRIPT"
else
  echo "Transcript differs from golden; diff follows:" >&2
  diff -u "$ROOT/bench/cmd/conformance/golden-dev.jsonl" "$TRANSCRIPT" >&2 || true
  exit 1
fi
