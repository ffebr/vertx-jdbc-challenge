#!/usr/bin/env bash
# The same entrypoint is used locally and by the fork's GitHub Actions workflow.
set -euo pipefail
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_dir="$(git -C "$app_dir" rev-parse --show-toplevel)"
mkdir -p "$app_dir/reports" "$app_dir/dist"

major="$(psql -X -qAt -v ON_ERROR_STOP=1 -d postgres -c 'SHOW server_version_num')"
[[ "$major" =~ ^18[0-9]{4}$ ]] || { echo 'CI requires PostgreSQL 18' >&2; exit 1; }

cd "$app_dir"
./dev/sbt.sh jdbc/scalafmtCheck native/scalafmtCheck \
  jdbc/Test/scalafmtCheck native/Test/scalafmtCheck scalafmtSbtCheck \
  jdbc/test native/test jdbc/stage native/stage 2>&1 | tee reports/build.log
python3 dev/check-runtime.py

for variant in jdbc native; do
  cd "$repo_dir"
  bash test/run.sh "$app_dir/$variant" 2>&1 | tee "$app_dir/reports/original-$variant.log"
  bash "$app_dir/dev/transcript.sh" "$variant" "$app_dir/reports/$variant.jsonl" \
    2>&1 | tee "$app_dir/reports/transcript-$variant.log"
  bash "$app_dir/ci/package.sh" "$variant" "$app_dir/dist/conduit-$variant.tar.gz"
done
cmp "$app_dir/reports/jdbc.jsonl" "$app_dir/reports/native.jsonl"
cd "$app_dir/dist"
sha256sum conduit-jdbc.tar.gz conduit-native.tar.gz > SHA256SUMS
sha256sum --check SHA256SUMS
