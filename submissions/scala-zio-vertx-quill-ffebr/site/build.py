#!/usr/bin/env python3
"""Build the self contained static evidence page (Python standard library only)."""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

APP = Path(__file__).resolve().parent.parent
SITE = APP / "site"
DRIVER_SHA = "5a29bb669109b81694a763ade3e6221f861ef702"


def between(source, start, end):
    a = source.find(start)
    if a < 0:
        raise ValueError(f"source marker not found: {start}")
    b = source.find(end, a + len(start))
    if b < 0:
        raise ValueError(f"source marker not found after {start}: {end}")
    return source[a:b].strip()


def read(rel):
    return (APP / rel).read_text(encoding="utf-8")


def snippets():
    user = read("shared/src/main/scala/conduit/persistence/QuillUserRepository.scala")
    return {
        "USER_LOOKUP": between(user, "override def byEmail", "override def byUsername"),
        "NATIVE_DATABASE": read("native/src/main/scala/conduit/persistence/Database.scala").strip(),
    }


def source_sha(explicit):
    value = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=APP, text=True
    ).strip()
    dirty = bool(
        subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=APP, text=True
        ).strip()
    )
    if not re.fullmatch(r"[0-9a-fA-F]{40}", value):
        raise ValueError("checked out git HEAD is not a 40-character SHA")
    if explicit and not re.fullmatch(r"[0-9a-fA-F]{40}", explicit):
        raise ValueError("source SHA must be exactly 40 hexadecimal characters")
    if explicit and explicit.lower() != value.lower():
        raise ValueError("--source-sha must match the checked out git HEAD")
    return value.lower(), dirty


def driver_sha():
    build = read("build.sbt")
    match = re.search(r"zio-quill-vertx\.git#([0-9a-fA-F]{40})", build)
    if not match:
        raise ValueError("could not find the pinned Quill source SHA in build.sbt")
    pinned = match.group(1).lower()
    if pinned != DRIVER_SHA:
        raise ValueError(
            f"pinned Quill SHA {pinned} does not match report SHA {DRIVER_SHA}"
        )
    return pinned


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--output", default="site/_build")
    ap.add_argument("--source-sha")
    ap.add_argument("--validation-url", default="")
    args = ap.parse_args()
    try:
        sha, dirty = source_sha(args.source_sha)
        pinned_driver = driver_sha()
        validation = args.validation_url
        if validation and not re.fullmatch(
            r"https://github\.com/ffebr/vertx-jdbc-challenge/actions/runs/[0-9]+",
            validation,
        ):
            raise ValueError("validation URL must be an ffebr/vertx-jdbc-challenge Actions run URL")
        out_arg = Path(args.output)
        if not out_arg.is_absolute():
            out_arg = APP / out_arg
        # Reject symlinked output paths before resolving them; deletion must never
        # follow a caller supplied link, even if its target happens to be under site/.
        cursor = out_arg
        while cursor != cursor.parent:
            if cursor.is_symlink():
                raise ValueError("output path must not contain symlinks")
            cursor = cursor.parent
        out = out_arg.resolve()
        if out == SITE or not out.is_relative_to(SITE.resolve()):
            raise ValueError("output must be a child directory of site/")
        if out.parent != SITE.resolve() or out.name not in {"_build", "_preview"}:
            raise ValueError("output must be site/_build or site/_preview")
        code = snippets()
        template = (SITE / "index.html").read_text(encoding="utf-8")
        values = {k: html.escape(v, quote=False) for k, v in code.items()}
        values.update(
            {
                "APP_COMMIT": html.escape(sha),
                "DRIVER_COMMIT": pinned_driver,
                "VALIDATION_URL": html.escape(validation or "#evidence"),
                "BUILD_STATUS": (
                    "Проверка этой ревизии: CI"
                    if validation and not dirty
                    else "Локальный черновик"
                ),
            }
        )
        for key, val in values.items():
            template = template.replace("{{" + key + "}}", val)
        unresolved = re.findall(r"\{\{[A-Z][A-Z0-9_]*\}\}", template)
        if unresolved:
            raise ValueError("unresolved template placeholders: " + ", ".join(sorted(set(unresolved))))
        if out.exists() or out.is_symlink():
            if out.is_symlink() or not out.is_dir():
                raise ValueError("output exists and is not a regular directory")
            shutil.rmtree(out)
        out.mkdir(parents=True)
        source_assets = SITE / "assets"
        if not source_assets.is_dir() or source_assets.is_symlink():
            raise ValueError("site/assets must be a regular directory")
        for item in source_assets.rglob("*"):
            if item.is_symlink():
                raise ValueError(f"refusing symlink in site/assets: {item.relative_to(SITE)}")
        shutil.copytree(source_assets, out / "assets")
        (out / "index.html").write_text(template, encoding="utf-8")
        (out / ".nojekyll").write_text("", encoding="utf-8")
        manifest = {
            "source_sha": sha,
            "driver_sha": pinned_driver,
            "validation_url": validation,
            "local_draft": bool(dirty or not validation),
            "snippet_sha256": {
                key: hashlib.sha256(value.encode()).hexdigest()
                for key, value in sorted(code.items())
            },
        }
        (out / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        print(f"Built {out}")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"build.py: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
