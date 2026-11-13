#!/usr/bin/env bash
set -euo pipefail
# Run once as root on the challenge's clean Ubuntu 24.04 image.
apt-get update
apt-get install --yes openjdk-17-jdk-headless curl ca-certificates git
java -version
