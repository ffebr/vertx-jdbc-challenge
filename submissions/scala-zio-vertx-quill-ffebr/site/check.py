#!/usr/bin/env python3
"""Small structural audit for a generated static page."""
from html.parser import HTMLParser
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

REQUIRED = {
    "overview",
    "architecture",
    "queries",
    "async",
    "bindings",
    "resources",
    "integration",
    "review",
    "evidence",
    "limits",
}


class Audit(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids, self.links, self.runtime = [], [], []
        self.lang, self.title = None, False
        self.in_title = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "html":
            self.lang = a.get("lang")
        if "id" in a:
            self.ids.append(a["id"])
        if tag == "title":
            self.in_title = True
        if tag in ("a", "link") and a.get("href"):
            self.links.append((tag, a["href"], a.get("rel", "").lower()))
        if tag in {"script", "img", "iframe", "source", "audio", "video"}:
            for attr in ("src", "poster"):
                if a.get(attr):
                    self.runtime.append((tag, a[attr]))
        runtime_relations = {"stylesheet", "preload", "modulepreload", "icon", "manifest"}
        if (
            tag == "link"
            and a.get("href")
            and a.get("rel", "").lower() in runtime_relations
        ):
            self.runtime.append((tag, a["href"]))

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False

    def handle_data(self, data):
        if self.in_title and data.strip():
            self.title = True


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "site/_build").resolve()
    page = root / "index.html"
    errors = []
    if not page.is_file():
        print(f"check.py: missing {page}", file=sys.stderr)
        return 2
    text = page.read_text(encoding="utf-8")
    parser = Audit()
    parser.feed(text)
    if parser.lang != "ru":
        errors.append("html lang must be ru")
    if not parser.title:
        errors.append("document title is missing")
    ids = set(parser.ids)
    if len(ids) != len(parser.ids):
        errors.append("duplicate element IDs")
    missing = sorted(REQUIRED - ids)
    if missing:
        errors.append("missing required section ids: " + ", ".join(missing))
    for tag, url, rel in parser.links:
        parts = urlsplit(url)
        if parts.scheme or parts.netloc or url.startswith(("data:", "mailto:", "tel:")):
            if parts.fragment and parts.netloc and parts.netloc.lower() in {"github.com"}:
                continue
            continue
        if parts.path:
            target = (root / unquote(parts.path)).resolve()
            if not target.is_relative_to(root) or not target.exists():
                errors.append(f"missing or unsafe local {tag} target: {url}")
            elif parts.fragment and target == page and unquote(parts.fragment) not in ids:
                errors.append(f"missing fragment: {url}")
        elif parts.fragment and unquote(parts.fragment) not in ids:
            errors.append(f"missing fragment: {url}")
    if re.search(r"\{\{[A-Z][A-Z0-9_]*\}\}", text):
        errors.append("unresolved template placeholder")
    for tag, ref in parser.runtime:
        if urlsplit(ref).scheme in {"http", "https"} or ref.startswith("//"):
            errors.append(f"external runtime asset ({tag}): {ref}")
            continue
        parts = urlsplit(ref)
        if parts.path:
            target = (root / unquote(parts.path)).resolve()
            if not target.is_relative_to(root) or not target.is_file():
                errors.append(f"missing or unsafe runtime asset ({tag}): {ref}")
    if errors:
        for error in errors:
            print("FAIL: " + error, file=sys.stderr)
        return 1
    print(f"Static audit passed: {page}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
