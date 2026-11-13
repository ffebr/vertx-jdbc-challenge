#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -d "$here/target/stage/lib" ]] || { echo 'Run build.sh first' >&2; exit 1; }
exec java -Xmx2g -Duser.timezone=UTC -cp "$here/target/stage/lib/*" conduit.Main
