#!/usr/bin/env python3
"""Check the staged executables' actual runtime dependency boundary."""
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent.parent


def entries(variant: str) -> set[str]:
    libraries = sorted((ROOT / variant / "target/stage/lib").glob("*.jar"))
    if not libraries:
        raise SystemExit(f"Missing {variant} stage; run build.sh first")
    result: set[str] = set()
    for library in libraries:
        with ZipFile(library) as archive:
            result.update(archive.namelist())
    return result


jdbc = entries("jdbc")
native = entries("native")
required_jdbc = {
    "org/postgresql/Driver.class",
    "com/zaxxer/hikari/HikariDataSource.class",
}
if not required_jdbc <= jdbc:
    raise SystemExit("JDBC stage is missing its PostgreSQL driver or connection pool")
forbidden = required_jdbc | {"META-INF/services/java.sql.Driver"}
if forbidden & native:
    raise SystemExit(f"JDBC dependency in native stage: {sorted(forbidden & native)}")
if "io/vertx/pgclient/PgConnection.class" not in native:
    raise SystemExit("Native stage is missing the Vert.x PostgreSQL client")
print("Runtime boundary verified: JDBC has PostgreSQL/Hikari; native has Vert.x and no JDBC driver")
