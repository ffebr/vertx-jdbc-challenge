#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <jdbc|native> [output.tar.gz]" >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 2
fi

variant="$1"
case "$variant" in
  jdbc|native) ;;
  *) usage; echo "Unsupported variant: $variant" >&2; exit 2 ;;
esac

app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_dir="$(git -C "$app_dir" rev-parse --show-toplevel)"
source_commit="$(git -C "$repo_dir" rev-parse HEAD)"
source_dirty=false
if [[ -n "$(git -C "$repo_dir" status --porcelain -- "$app_dir")" ]]; then source_dirty=true; fi
quill_pin="$(sed -nE 's/.*zio-quill-vertx\.git#([0-9a-f]{40}).*/\1/p' "$app_dir/build.sbt" | head -n 1)"
if [[ ! "$quill_pin" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Could not read the pinned Quill source commit from build.sbt" >&2
  exit 1
fi

stage_lib="$app_dir/$variant/target/stage/lib"
if [[ ! -d "$stage_lib" ]]; then
  echo "Missing staged runtime libraries: $stage_lib (build and stage $variant first)" >&2
  exit 1
fi
shopt -s nullglob
jars=("$stage_lib"/*.jar)
if (( ${#jars[@]} == 0 )); then
  echo "No staged runtime jars found in $stage_lib" >&2
  exit 1
fi

output="${2:-$app_dir/dist/conduit-${variant}-${source_commit:0:12}.tar.gz}"
mkdir -p "$(dirname "$output")"
output="$(cd "$(dirname "$output")" && pwd)/$(basename "$output")"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
package_dir="$tmp_dir/conduit-${variant}"
mkdir -p "$package_dir/lib" "$package_dir/bin"
cp -- "${jars[@]}" "$package_dir/lib/"

cat >"$package_dir/bin/start.sh" <<'START'
#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec java -Xmx2g -Duser.timezone=UTC -cp "$here/lib/*" conduit.Main
START
chmod 755 "$package_dir/bin/start.sh"
{
  printf '# Conduit %s runtime package\n\n' "$variant"
  cat <<'README'

This archive contains the selected application variant and its runtime jars.
It does not contain source code, credentials, or a database. Java 17 or newer
is required.

Set the four application environment variables and start the foreground JVM:

```sh
export DATABASE_URL='postgres://conduit:conduit@db-host:5432/conduit'
export JWT_SECRET='replace-with-a-long-random-secret'
export HOST='0.0.0.0'
export PORT='3000'
./bin/start.sh
```

The database must already have the challenge schema and seed data. The app
does not create or migrate the schema. Send SIGTERM to stop the process.
See the source submission README for API and implementation details.
README
} >"$package_dir/README.md"
cat >"$package_dir/MANIFEST.txt" <<EOF
variant=${variant}
source_commit=${source_commit}
source_dirty=${source_dirty}
quill_source_commit=${quill_pin}
EOF
(cd "$package_dir" && find README.md MANIFEST.txt bin/start.sh lib -type f -print0 \
  | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS)

source_epoch="$(git -C "$repo_dir" show -s --format=%ct "$source_commit")"
tar --sort=name --mtime="@${source_epoch}" --owner=0 --group=0 --numeric-owner \
  --mode='u+rwX,go+rX,go-w' -C "$tmp_dir" -cf - "conduit-${variant}" \
  | gzip -n > "$output"
echo "Wrote $output"
