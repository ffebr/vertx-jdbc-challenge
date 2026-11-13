# Application delivery backlog

The application is conventional layered code: Tapir handles HTTP and wire types;
services enforce validation, authentication and ownership; repositories contain
Quill QDSL; each executable owns its connection resources. Shared sources compile
independently against JDBC and native Vert.x contexts. No data or authentication
caches, schema changes, or benchmark-specific execution paths are allowed.

Luna agents own bounded areas; the coordinating agent owns build execution,
integration, review, and acceptance. An agent report alone does not close a task.

| Work item | Owner | Acceptance |
| --- | --- | --- |
| HTTP boundary and DTOs | Luna HTTP | All 19 endpoints; JSON errors; exact null and timestamp representation |
| Services and authentication | Luna services | Per-request HS256 verification, bcrypt cost 10, validation and ownership |
| User QDSL | Luna user queries | Shared query source; generated IDs; nullable updates; idempotent follows |
| Content QDSL | Luna content queries | Shared queries; body-free lists; correct filters/counts; atomic article/tag creation |
| PostgreSQL development harness | Luna test harness | PostgreSQL 18, verified original seed, disposable databases, unchanged upstream runner |
| Independent review | Luna reviewer | Findings checked against both SPEC.md and original conformance assertions |
| Dual compilation and packaging | Coordinator | Both executables build with the pinned Quill revision and own dependencies |
| Security checks | Coordinator | Shared security/validation tests pass in both builds |
| API acceptance | Coordinator | Original conformance runner passes separately for JDBC and native Vert.x |
| Output equivalence | Coordinator | Original normalized transcripts match the golden and each other |
| Delivery audit | Coordinator | Native runtime has no JDBC driver/Hikari; original schema/tests unchanged; documented commands |

Validation results and remaining limitations belong in README.md, with exact
commands and counts. Publishing or challenge submission is a separate action.

## Acceptance record

All delivery items above are complete: both original API runs pass 197 steps,
both shared unit runs pass 9 tests, both normalized transcripts match the golden,
and the runtime boundary and formatting checks pass. The default remote source
pin builds both executors without registry credentials.

The application exposed and now uses a native PostgreSQL optional-fallback fix
on `ffebr/zio-quill-vertx`, branch `fix/postgres-option-fallback`, commit
`4dcd91128fda7d79e0614b1cb6abe2ca35258d6c`. Its PostgreSQL suite passes 178
tests. The published alpha.1 is unchanged; a future driver release still follows
the fork's complete release matrix. The follow-up delivery task adds portable CI,
checksummed runtime distributions, tested nonroot images and fork-only GHCR
delivery after successful verification. Load benchmarking remains separate.
