#!/usr/bin/env bash
# Credentials are supplied by GitHub Actions only after the verification job.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${GITHUB_REPOSITORY:-}" == ffebr/vertx-jdbc-challenge ]] || {
  echo 'Image publication is restricted to this fork in GitHub Actions' >&2; exit 1;
}
[[ "${GITHUB_EVENT_NAME:-}" == push && "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || exit 1
case "${GITHUB_REF:-}" in
  refs/heads/main) channel=main ;;
  refs/tags/conduit-v*)
    channel="${GITHUB_REF#refs/tags/conduit-v}"
    [[ "$channel" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ ]] || exit 1
    ;;
  *) echo 'Only main and Conduit release tags can publish images' >&2; exit 1 ;;
esac
for variant in jdbc native; do
  local_image="conduit:$variant"
  revision="$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$local_image")"
  [[ "$revision" == "$GITHUB_SHA" ]] || { echo 'Image source revision mismatch' >&2; exit 1; }
  repository="ghcr.io/ffebr/vertx-jdbc-challenge-$variant"
  docker tag "$local_image" "$repository:sha-$GITHUB_SHA"
  docker tag "$local_image" "$repository:$channel"
  docker push "$repository:sha-$GITHUB_SHA"
  docker push "$repository:$channel"
done
