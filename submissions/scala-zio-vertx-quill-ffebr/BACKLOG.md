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

Both original API runs pass 197 steps and both normalized transcripts match
one another and the unchanged golden. The unit suites now pass 14 JDBC and
16 native tests, including URL credentials with plus signs, reserved characters
and Unicode. Runtime and formatting checks pass for both variants.

The narrow optional-fallback patch at `4dcd9112` has been replaced by general
native parameter binding at `5a29bb669109b81694a763ade3e6221f861ef702`.
The new driver regressions cover optional predicates, mapped/custom encoders,
decimal/array values, JSON/JSONB, prepared/batch execution, cursor streams and
enum inference. PostgreSQL passes 197 tests per Scala version; MySQL and MariaDB
each pass 21. The release workflow still requires DB2 on both Scala versions.

The app source pin uses that revision. Published mode resolves the native
runtime from GitHub Packages, while JDBC retains the same source revision.
The driver's published-consumer workflow verifies the source pin against the
release tag, audits exact runtime versions and runs the original API/transcript
checks. Registry publication and fork-only GHCR delivery remain CI actions;
load benchmarking remains separate.
