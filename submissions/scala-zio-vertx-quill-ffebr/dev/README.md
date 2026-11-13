# Dev database and conformance

## Portable transcript helper

`transcript.sh` uses the caller's installed `go` and `psql`, plus standard
libpq settings (`PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`). By default it
does not start or configure PostgreSQL. Point it at a PostgreSQL 18 server with
the deterministic `conduit_dev` seed database already loaded. The connecting
role needs permission to create and drop databases, and the app role
`conduit` with password `conduit` must exist.

```sh
dev/transcript.sh jdbc
dev/transcript.sh native
```

It makes a fresh copy of `conduit_dev`, starts the already staged app on
`127.0.0.1` at the port in `PORT` (default 3000), runs the existing normalized
conformance CLI, and compares the output with
`bench/cmd/conformance/golden-dev.jsonl`. Supply an optional transcript path
as the second argument. On any failure after the app starts, its server log is
saved alongside the transcript as `<transcript>.server.log`.

## Managed workspace setup

The `--local` option opts into the managed workspace's dedicated Docker-backed
PostgreSQL 18 instance and pinned Go/psql wrapper:

```sh
bash submissions/scala-zio-vertx-quill-ffebr/dev/postgres18.sh --seed
dev/transcript.sh --local jdbc
dev/transcript.sh --local native
```

The seed command runs the original `seed/make-seed.sh --dev`; its fingerprint
check must print `OK: conduit_dev matches the reference seed`. The local
database listens on `127.0.0.1:55438`. Its files are isolated under
`/workspace/.setup/challenge/postgres18-data`; the container is labeled for
this submission and mounts the repository read-only so the seed script's
client-side `\copy` can read CSV files.

## Original conformance gate

The repository's unchanged `test/run.sh` remains the primary gate. It builds
and starts each submission against a fresh seed copy and runs all 197
conformance steps:

```sh
bash test/run.sh submissions/scala-zio-vertx-quill-ffebr/jdbc
bash test/run.sh submissions/scala-zio-vertx-quill-ffebr/native
```

For `--local`, export the managed workspace's psql wrapper, Go, GOPATH, and
GOCACHE before running those commands:

```sh
export PATH="/workspace/.setup/challenge/bin:/workspace/.setup/challenge/go/bin:$PATH"
export GOPATH=/workspace/.setup/challenge/go-work
export GOCACHE=/workspace/.setup/challenge/go-cache
export PGHOST=127.0.0.1 PGPORT=55438 PGUSER=postgres PGPASSWORD=postgres-dev-only
```
