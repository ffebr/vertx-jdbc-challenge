# Runtime distributions

The two variants can be packaged independently after their runtime libraries
have been staged. Packaging does not compile code or download dependencies:

```sh
./ci/package.sh jdbc
./ci/package.sh native
```

Each command writes a deterministic `dist/conduit-<variant>-<source-commit>.tar.gz`
containing only `lib/`, `bin/start.sh`, run instructions, a manifest with the
challenge source commit and pinned Quill source commit, and `SHA256SUMS` for
the package contents. Supply a different output path as the second argument
when desired. Verify the unpacked files with `sha256sum -c SHA256SUMS` from the
package directory.

The manifest records `source_dirty=true` for local packages made from an
uncommitted application checkout. CI packages identify the checked-out commit.

The package README documents the required `DATABASE_URL`, `JWT_SECRET`,
`HOST`, and `PORT` variables. For example,
`DATABASE_URL=postgres://conduit:conduit@db-host:5432/conduit` uses the same
URL contract for both JDBC and native variants. The schema and seed must be
prepared separately.

## Runtime container

Build from this directory after staging the requested variant. The build
context allowlist only exposes the Dockerfile and staged runtime jars:

```sh
docker build --build-arg VARIANT=jdbc -t conduit:jdbc .
docker build --build-arg VARIANT=native -t conduit:native .
```

The image contains a Java 17 runtime and the selected staged jars. It runs as
UID/GID 10001, binds to `0.0.0.0:3000` by default, and runs Java in the
foreground so container SIGTERM reaches the application. Set `DATABASE_URL`
and `JWT_SECRET` at runtime; neither is baked into the image. `HOST` and
`PORT` can also be overridden. The image does not install build tools, fetch
dependencies, run migrations, or define a health check that queries the
database.

## GitHub Actions delivery

The fork's `Conduit JDBC and Vert.x` workflow runs on relevant pushes and pull
requests, and can also be started manually. It:

1. Loads the original deterministic seed into a digest-pinned PostgreSQL 18.
2. Checks formatting, both shared unit suites, and the native dependency boundary.
3. Runs the unchanged `test/run.sh` for each backend on a fresh database.
4. Compares each normalized transcript with the original golden file and each other.
5. Produces both runtime archives and their checksums.
6. Builds both nonroot images, then runs the original conformance CLI and golden
   comparison against each image on another fresh database.
7. Uploads distributions, reports and the tested images as workflow artifacts.

After verification, pushes to this fork's `main` publish those exact saved
images to:

```text
ghcr.io/ffebr/vertx-jdbc-challenge-jdbc:main
ghcr.io/ffebr/vertx-jdbc-challenge-native:main
```

Each image also has a `sha-<full-source-commit>` tag. A `conduit-v1.2.3` tag
runs all the same checks and publishes image tag `1.2.3`. Pull requests and
ordinary branches only verify and upload artifacts. The delivery job uses
GitHub's built-in `GITHUB_TOKEN`, scoped to package writes in this repository;
no separate publishing secret is needed. Private packages require authenticated
pulls. No application credentials are included in the images.

Only `ffebr/vertx-jdbc-challenge` can publish through this workflow. The inherited
all-submissions workflow is skipped in this fork so a CI edit does not build
every unrelated language submission. The challenge's schema, seed and API tests
remain shared and unchanged.

To reproduce the hosted verification against an existing PostgreSQL 18 seed,
set the standard libpq variables, ensure Java 17, Go 1.27.1, psql, curl and
Python 3 are on PATH, and run `bash ci/verify.sh`. For a delivery image check,
set `CONDUIT_POSTGRES_CONTAINER` to the PostgreSQL service container ID and run
`bash ci/check-container.sh native conduit:native` (or the JDBC equivalent).
