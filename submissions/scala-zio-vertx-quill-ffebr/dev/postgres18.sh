#!/usr/bin/env bash
# Start the isolated PostgreSQL 18 dev database. Run with --seed to create the
# deterministic conduit_dev database using the repository's original seed script.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SETUP=/workspace/.setup/challenge
IMAGE='postgres:18-bookworm@sha256:ece55f7868da684e400f82f1030a050718adbdd65c23aa500c486f6e2950dc0f'
NAME=scala-zio-vertx-quill-dev-pg18
LABEL=org.openai.challenge=scala-zio-vertx-quill-ffebr-dev
DOCKER=(env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker --host=unix:///var/run/docker.sock)

mkdir -p "$SETUP/postgres18-data" "$SETUP/bin"
if "${DOCKER[@]}" container inspect "$NAME" >/dev/null 2>&1; then
  actual_label=$("${DOCKER[@]}" inspect --format '{{ index .Config.Labels "org.openai.challenge" }}' "$NAME")
  actual_mount=$("${DOCKER[@]}" inspect --format '{{ range .Mounts }}{{ .Source }}:{{ .Destination }}:{{ .RW }} {{ end }}' "$NAME")
  actual_ports=$("${DOCKER[@]}" inspect --format '{{ json .HostConfig.PortBindings }}' "$NAME")
  [[ "$actual_label" == scala-zio-vertx-quill-ffebr-dev && "$actual_mount" == *"$SETUP/postgres18-data:/var/lib/postgresql:true"* && "$actual_mount" == *"$ROOT:/workspace/vertx-jdbc-challenge:false"* ]] || {
    echo "Existing container $NAME does not match its ownership label and mounts; refusing to use it." >&2; exit 1; }
  [[ "$actual_ports" == *'127.0.0.1'* && "$actual_ports" == *'55438'* ]] || { echo "Existing container has unexpected published ports; refusing to use it." >&2; exit 1; }
  "${DOCKER[@]}" start "$NAME" >/dev/null
else
  "${DOCKER[@]}" run -d --name "$NAME" --label "$LABEL" \
    --publish 127.0.0.1:55438:5432 \
    --mount "type=bind,src=$SETUP/postgres18-data,dst=/var/lib/postgresql" \
    --mount "type=bind,src=$ROOT,dst=/workspace/vertx-jdbc-challenge,readonly" \
    --env POSTGRES_PASSWORD=postgres-dev-only \
    "$IMAGE" >/dev/null
fi

for _ in $(seq 1 60); do
  if "${DOCKER[@]}" exec "$NAME" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1; then break; fi
  sleep 1
done
"${DOCKER[@]}" exec "$NAME" pg_isready -U postgres -h 127.0.0.1

cat > "$SETUP/bin/psql" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
exec env PGHOST=127.0.0.1 PGPORT=5432 PGUSER=postgres PGPASSWORD=postgres-dev-only \
  env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
  docker --host=unix:///var/run/docker.sock exec -i \
  -e PGHOST=127.0.0.1 -e PGPORT=5432 -e PGUSER=postgres -e PGPASSWORD=postgres-dev-only \
  scala-zio-vertx-quill-dev-pg18 psql "$@"
WRAPPER
chmod 755 "$SETUP/bin/psql"

if [[ "${1:-}" == --seed ]]; then
  PATH="$SETUP/bin:$SETUP/go/bin:$PATH" GOCACHE="$SETUP/go-cache" GOPATH="$SETUP/go-work" PGHOST=127.0.0.1 PGPORT=55438 \
    bash "$ROOT/seed/make-seed.sh" --dev
fi
echo "PostgreSQL 18 is ready. App DATABASE_URL=postgres://conduit:conduit@127.0.0.1:55438/conduit_dev"
echo "Use dev/README.md for original conformance commands (psql wrapper and official Go are under $SETUP)."
