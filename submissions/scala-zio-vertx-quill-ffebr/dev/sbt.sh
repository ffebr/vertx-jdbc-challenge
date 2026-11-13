#!/usr/bin/env bash
set -euo pipefail
set -- -Dmodules=base -Dquill.scala.version=2.13.16 "$@"
if [[ -n "${SBT:-}" ]]; then exec "$SBT" "$@"; fi
if [[ -x /workspace/.setup/quill/bin/sbt ]]; then exec /workspace/.setup/quill/bin/sbt "$@"; fi
cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/ffebr-conduit/sbt-1.11.4"
launcher="$cache_dir/sbt-launch.jar"
mkdir -p "$cache_dir"
if [[ ! -f "$launcher" ]]; then
  curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/1.11.4/sbt-launch-1.11.4.jar --output "$launcher"
fi
printf '%s  %s\n' a566d4b8958b9dd548761ea357b637afb4c0f20da26244ed86f9b11adbaf1cc9 "$launcher" | sha256sum --check --status
exec java -Xmx3g -Xss5m -Dsbt.supershell=false -Dquill.macro.log=false -jar "$launcher" "$@"
