# Conduit native

Front: nginx

This variant compiles the common Scala/ZIO/Tapir/Vert.x application and common
Quill query sources with the native PostgreSQL executor. See the parent
README for versions, architecture, build prerequisites and conformance results.

Build: `bash build.sh`. Run in the foreground: `bash start.sh`.
Runtime settings: `DATABASE_URL`, `JWT_SECRET`, `HOST`, `PORT` only.
