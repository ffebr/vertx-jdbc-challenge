#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec ./dev/sbt.sh jdbc/stage native/stage
