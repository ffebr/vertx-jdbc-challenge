# Conduit with shared Quill queries

Front: nginx

One Scala 2.13.16 / ZIO 2.1.20 / Tapir 1.11.44 / Vert.x 4.5.36 application,
compiled into JDBC and native Vert.x PostgreSQL executables. The HTTP,
domain, service and QDSL repository files are shared sources compiled twice.
Only `persistence.Database` and dependency wiring differ.

Both executors use the fork's pinned source commit
`5a29bb669109b81694a763ade3e6221f861ef702`. The JDBC executor is the original
Quill JDBC/ZIO implementation; the native executor includes the PostgreSQL
typed-parameter fix discovered by this application. The shared core and
`zio.quill` namespace are identical. The old upstream 4.8.5 binary uses a
different `io.getquill` namespace and would obscure that comparison.

The published native `0.1.0-alpha.1` has a PostgreSQL `42P18` failure for certain
lifted optional updates. This app uses the corrected source revision, with
unchanged `Option.getOrElse` / `orElse` QDSL. The fix also covers optional
filters, decimal and array parameters, JSON/JSONB, and cursor streams.
Registry releases must pass the driver's database/Scala CI matrix; the separate
published-consumer check below verifies the delivered artifacts.

## Read the code

Start at `shared/src/main/scala/conduit/Main.scala`, then follow the request
through `http/HttpApi.scala`, `service/ConduitService.scala` and the
`persistence/QuillUserRepository.scala` / `QuillContentRepository.scala` classes.
The latter uses named article projections: summary rows intentionally omit the
body, while full rows include it. Backend-specific connection and error handling
live in `jdbc/` and `native/` versions of `persistence.Database`.

[REVIEW.md](REVIEW.md) records the readability review, changes and acceptance
results. The [engineering report](https://ffebr.github.io/vertx-jdbc-challenge/)
is built from `site/` with diagrams, source excerpts, parameter-binding examples
and CI evidence. See [site/DEPLOYMENT.md](site/DEPLOYMENT.md) for preview and
GitHub Pages setup.

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

### Published native consumer check

The normal app build remains source-pinned and needs no GitHub Packages
credentials. To opt in to a released native Vert.x driver artifact, pass its
published version to sbt and provide read access to the driver's GitHub
Packages repository:

```sh
GITHUB_PACKAGES_USER='<github-username>' \
GITHUB_PACKAGES_TOKEN='<read:packages-token>' \
./dev/sbt.sh '-Dconduit.quill.published.version=<published-version>' native/test native/stage
```

This option changes only the native executor dependency. JDBC continues to use
the pinned source project, and omitting the option leaves both variants on the
default source-pinned build.

The driver repository's
`.github/workflows/vertx-published-consumer.yml` runs after a successful
`Native Vert.x` release workflow. It resolves the app's `main` commit once,
then builds and tests both backends from that exact commit against the
published native artifact. The check stages and audits both runtime closures,
runs the original API conformance and golden transcript checks, and verifies
the produced packages. It uses the driver repository's own `GITHUB_TOKEN` with
`packages: read`; no app-side package token is needed. The workflow also
supports manual dispatch with an immutable release version and app commit.

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

Validated on 2026-11-14 against the pinned driver revision through a local
checkout and the original PostgreSQL 18 development seed. CI repeats the checks
using the default remote source pin:

| Check | JDBC | Native Vert.x |
| --- | --- | --- |
| Original `test/run.sh` conformance | 197 / 197 | 197 / 197 |
| Security, validation, DTO and connection URL unit tests | 14 / 14 | 16 / 16 |
| Normalized transcript versus original golden | byte-identical | byte-identical |
| Formatting and standalone staging | passed | passed |

Both transcripts and `bench/cmd/conformance/golden-dev.jsonl` have SHA-256
`b017dae7bc5b43f6321a4722ef0d95cea112e73cb66e7590ed12b5bc1e256878`.
The challenge schema, specifications, original tests and golden file are unchanged.
The PostgreSQL-focused driver suite passed 197 tests on Scala 2.13.16 and
2.12.20, including lifted NULLs, mapped encoders, decimal/array parameters,
JSON/JSONB, prepared execution, streaming and enum comparisons. MySQL and
MariaDB each passed another 21 tests on both Scala versions. DB2 remains a
required release CI job; its restored local database has a filesystem I/O error.

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
