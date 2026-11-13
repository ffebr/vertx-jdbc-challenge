# Conduit with shared Quill queries

Front: nginx

One Scala 2.13.16 / ZIO 2.1.20 / Tapir 1.11.44 / Vert.x 4.5.36 application,
compiled into JDBC and native Vert.x PostgreSQL executables. The HTTP,
domain, service and QDSL repository files are shared sources compiled twice.
Only `persistence.Database` and dependency wiring differ.

Both executors use the fork's pinned source commit
`4dcd91128fda7d79e0614b1cb6abe2ca35258d6c`. The JDBC executor is the original
Quill JDBC/ZIO implementation; the native executor includes the PostgreSQL
optional-fallback fix discovered by this application. The shared core and
`zio.quill` namespace are identical. The old upstream 4.8.5 binary uses a
different `io.getquill` namespace and would obscure that comparison.

The published native `0.1.0-alpha.1` has a PostgreSQL `42P18` failure for certain
lifted optional updates. This app uses the corrected source revision, with
unchanged `Option.getOrElse` / `orElse` QDSL. The fix is on the user's fork in
`master` and `fix/postgres-option-fallback`; it is not a new registry release.

## Build and run

Run `install.sh` once on a clean box, then `build.sh` as a normal user.
Java 17 or newer is required. The sbt 1.11.4 launcher is checksum-pinned. Builds
fetch the pinned source from the public fork and dependencies from Maven
repositories; no GitHub Packages credentials or prebuilt local Quill jars are
required. A development-only `-Dconduit.quill.source=/path/to/checkout` override
is available for working on the driver. Acceptance uses the default remote pin.

`jdbc/build.sh` / `jdbc/start.sh` and `native/build.sh` / `native/start.sh`
are independent submission entrypoints for the original `test/run.sh` runner.
The parent `start.sh` defaults to native. All starts execute the JVM in the
foreground and use only DATABASE_URL, JWT_SECRET, HOST and PORT as configured
by the challenge. Each executable has a 16-connection database pool. Native
HTTP and SQL share the scoped Vert.x instance. JDBC runs blocking database
calls on ZIO's blocking executor; bcrypt is also explicitly blocking.

## Design

- Domain types and repository ports have no HTTP or backend dependencies.
- Typed Tapir endpoints map the wire protocol to service operations and keep
  password hashes and internal article ids out of response DTOs.
- Services validate commands, authenticate each request and enforce ownership.
- Shared Quill repositories implement actual joins, filters, subqueries,
  aggregation, pagination and writes. Tiny PostgreSQL functions are used for
  sequence allocation and server timestamps; endpoint SQL is not handwritten.
- Article/tag creation is transactional. A response follows commit. The
  application performs no schema migration or DDL: the fixed challenge schema
  is owned by the supplied database preparation tooling.
- No cross-request data/auth/bcrypt caches, batching across requests, custom
  protocol parser, server-wide tuning, schema changes or benchmark detection.

## Verification

Validated on 2026-11-13 with the default remote source pin and the original
PostgreSQL 18 development seed:

| Check | JDBC | Native Vert.x |
| --- | --- | --- |
| Original `test/run.sh` conformance | 197 / 197 | 197 / 197 |
| Shared security, validation and DTO unit tests | 9 / 9 | 9 / 9 |
| Normalized transcript versus original golden | byte-identical | byte-identical |
| Formatting and standalone staging | passed | passed |

Both transcripts and `bench/cmd/conformance/golden-dev.jsonl` have SHA-256
`b017dae7bc5b43f6321a4722ef0d95cea112e73cb66e7590ed12b5bc1e256878`.
The challenge schema, specifications, original tests and golden file are unchanged.
The PostgreSQL-focused driver suite also passed 178 tests on Scala 2.13.16,
including the new optional fallback and volatile expression regressions.

From this directory:

```sh
./dev/sbt.sh jdbc/test native/test jdbc/stage native/stage
python3 dev/check-runtime.py
```

`dev/check-runtime.py` inspects the staged jars: JDBC contains PostgreSQL JDBC
and Hikari; native contains the Vert.x PostgreSQL client and no JDBC driver SPI.
See [dev/README.md](dev/README.md) for isolated database setup, original runner
commands and transcript reproduction. See [BACKLOG.md](BACKLOG.md) for agent
ownership and acceptance gates. No load benchmark or throughput score is reported.

## CI/CD

The fork's [Conduit workflow](../../.github/workflows/conduit.yml) repeats
these checks on PostgreSQL 18, verifies both nonroot containers with the original
API suite, and uploads runtime archives, checksums and test evidence. Successful
pushes to `main` deliver the tested images to this fork's GHCR packages; release
tags use the `conduit-v<version>` convention. See [DISTRIBUTION.md](DISTRIBUTION.md)
for image names, packaging commands and publication conditions.
