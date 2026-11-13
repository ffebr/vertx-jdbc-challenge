#!/usr/bin/env bash
# Exercise the actual delivery image using the original conformance binary.
set -euo pipefail
variant="${1:?usage: ci/check-container.sh jdbc|native image}"
image="${2:?image is required}"
case "$variant" in jdbc|native) ;; *) echo 'Unknown variant' >&2; exit 2 ;; esac
postgres_container="${CONDUIT_POSTGRES_CONTAINER:?Set the PostgreSQL 18 service container ID}"
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_dir="$(git -C "$app_dir" rev-parse --show-toplevel)"
reports="$app_dir/reports"
mkdir -p "$reports"
tmp_dir="$(mktemp -d)"
database="conduit_image_${variant}_$$"
container="conduit-image-${variant}-$$"
port="${PORT:-3000}"
secret=multicore-server-challenge
psql_command=(psql -X -q -v ON_ERROR_STOP=1 -d postgres)

cleanup() {
  local rc=$?
  docker stop --time 15 "$container" >/dev/null 2>&1 || true
  docker logs "$container" > "$reports/container-$variant.server.log" 2>&1 || true
  docker rm --force "$container" >/dev/null 2>&1 || true
  "${psql_command[@]}" -c "DROP DATABASE IF EXISTS $database WITH (FORCE)" >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
  return "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

[[ "$(docker inspect --format '{{.Config.User}}' "$image")" == '10001:10001' ]] || {
  echo 'The application image must run as UID/GID 10001' >&2; exit 1;
}
read -r network address < <(
  docker inspect --format '{{json .NetworkSettings.Networks}}' "$postgres_container" |
    python3 -c 'import json,sys; name,details=next(iter(json.load(sys.stdin).items())); print(name,details["IPAddress"])'
)
[[ -n "$network" && -n "$address" ]] || { echo 'PostgreSQL service network is missing' >&2; exit 1; }
"${psql_command[@]}" -c "CREATE DATABASE $database TEMPLATE conduit_dev STRATEGY FILE_COPY"
docker run --detach --name "$container" --network "$network" \
  --publish "127.0.0.1:$port:3000" \
  --env "DATABASE_URL=postgres://conduit:conduit@$address:5432/$database" \
  --env "JWT_SECRET=$secret" "$image" >/dev/null
base="http://127.0.0.1:$port"
for _ in $(seq 60); do curl -sf -o /dev/null "$base/api/tags" && break; sleep 1; done
curl -sf -o /dev/null "$base/api/tags" || { docker logs "$container" >&2; exit 1; }
(cd "$repo_dir/bench" && go build -o "$tmp_dir/conformance" ./cmd/conformance)
"$tmp_dir/conformance" -base "$base" -secret "$secret" \
  -transcript "$reports/container-$variant.jsonl" -q
cmp "$repo_dir/bench/cmd/conformance/golden-dev.jsonl" "$reports/container-$variant.jsonl"
echo "Container conformance and golden transcript passed: $image"
