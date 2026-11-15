# Readability review

This pass reviews the shared Conduit application against its behavior at
`6b23740cf39dc6baab19f9c7f80e07572ecbd7c7`. Luna agents reviewed persistence,
services/authentication, HTTP/DTOs and startup separately. The coordinator
reviewed the changes and ran the shared acceptance commands.

## Changes

| Finding | Change | Behavior that must remain the same |
| --- | --- | --- |
| Article queries carried seven-field tuples and positional accesses such as `r._1.authorId`. | Named `ArticleViewRow` and `ArticleSummaryViewRow` projections expose article and author fields explicitly. A named `CommentViewRow` and one comment mapper also replace positional accesses in comment creation. Assemblers read named fields. | The list projection excludes `body`; tags load for the page in one query; filters, ordering, pagination, capped counts and transactions retain their meaning. |
| Optional user fields repeated nested `Option.fold` validation. | A small `optionalRequired` helper validates values only when supplied; password hashing and missing-user handling use explicit branches. | Missing fields preserve existing values; error messages, HTTP status mapping and password hashing rules remain the same. |
| Repeated missing-entity conversions obscured service operations. | A local `orNotFound` helper names the existing mapping. | Ownership checks and authorization errors retain their order and meaning. |

HTTP endpoints, DTOs, authentication parsing and resource acquisition were
reviewed and retained. Their explicit existing structure was clearer than a
new generic abstraction. JDBC and native `Database` implementations remain
separate: their environment types and resource acquisition differ.

## Validation

The following completed locally for this refactor:

- Both shared source builds compile: JDBC and native Vert.x.
- Unit suites: 14 JDBC and 16 native tests pass.
- The unchanged original conformance runner passes 197 steps for each variant.
- Both normalized transcripts match the original golden and each other.
- Runtime audit confirms PostgreSQL JDBC + Hikari in JDBC and no JDBC driver
  in native. Both standalone distributions pass checksum verification.

Run `bash ci/verify.sh` against the documented PostgreSQL 18 development seed
to repeat these checks. Hosted CI also verifies the two delivery containers.
The challenge specification, original schema, seed, API tests and golden
expectations are unchanged.

The report site extracts its Scala examples from these source files during
build. Its deployed page links to the exact application commit and validation
run, rather than relying on hand-copied examples.

This is a readability pass. It adds no result/authentication caches, changes no
password algorithm, introduces no benchmark-specific path, and makes no new
performance or universal compatibility claim. The driver remains pinned to
`5a29bb669109b81694a763ade3e6221f861ef702` (alpha.2).

## Report verification

The static report passed its structural/source-link audit and browser checks
at 320, 390, 768 and 1440 pixels. The binding explorer switches between the
actual two String parameters, without simulating a database call. The checked
page had no document overflow, JavaScript errors, failed runtime assets,
external runtime requests or violations in the performed axe WCAG A/AA audit.
These browser checks are local evidence, not a claim of accessibility certification.

## Hosted verification

For commit `4fe26a1ea8c704745c0aa2cc8c7167f074244916`, the
[application verification](https://github.com/ffebr/vertx-jdbc-challenge/actions/runs/43766761659/job/132973551635)
and [image delivery](https://github.com/ffebr/vertx-jdbc-challenge/actions/runs/43766761659/job/132974405447)
jobs succeeded. The hosted verification includes the original API and golden
checks against both nonroot delivery images. Pages is a separate deployment
job; its initial attempt built the report artifact but required the repository
administrator to enable the GitHub Actions publishing source before deployment.
