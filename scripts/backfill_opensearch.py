#!/usr/bin/env python3
"""
One-time backfill: read Supabase CSV exports and bulk-index into Aiven OpenSearch.

Flow
----
1. Create `portfolio_content_v1` (with mappings) if it does not exist.
2. Read each CSV, normalize to a common SearchDocument, strip HTML in `content`.
3. Bulk-index every row with id = "{SOURCE_TYPE}:{source_id}".
4. Atomically swap alias `portfolio_content_current` to point at `portfolio_content_v1`.

Env vars (required)
-------------------
OPENSEARCH_HOST       e.g. os-b4cbaea-yuqi-791c.a.aivencloud.com
OPENSEARCH_PORT       e.g. 27099
OPENSEARCH_USERNAME   e.g. avnadmin
OPENSEARCH_PASSWORD   (NEVER hardcode)

Optional
--------
OPENSEARCH_INDEX_VERSION   default portfolio_content_v1
OPENSEARCH_ALIAS           default portfolio_content_current
BLOGS_CSV                  default ../../Blogs_rows.csv
LIFE_BLOGS_CSV             default ../../life_blogs_rows.csv
PROJECTS_CSV               default ../../Projects_rows.csv

Run
---
    python3 -m venv .venv && source .venv/bin/activate
    pip install opensearch-py>=2.6.0
    export OPENSEARCH_PASSWORD='...'
    python scripts/backfill_opensearch.py
"""
from __future__ import annotations

import csv
import html
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

try:
    from opensearchpy import OpenSearch, helpers
except ImportError:
    sys.stderr.write(
        "Missing dependency. Run:\n"
        "  python3 -m venv .venv && source .venv/bin/activate\n"
        "  pip install 'opensearch-py>=2.6.0'\n"
    )
    sys.exit(1)


# -------- config -------------------------------------------------------------

HOST = os.environ["OPENSEARCH_HOST"]
PORT = int(os.environ.get("OPENSEARCH_PORT", "27099"))
USERNAME = os.environ.get("OPENSEARCH_USERNAME", "avnadmin")
PASSWORD = os.environ["OPENSEARCH_PASSWORD"]

INDEX_VERSION = os.environ.get("OPENSEARCH_INDEX_VERSION", "portfolio_content_v1")
ALIAS = os.environ.get("OPENSEARCH_ALIAS", "portfolio_content_current")

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PARENT = REPO_ROOT.parent  # ~/Documents/GitHub

BLOGS_CSV = Path(os.environ.get("BLOGS_CSV", DEFAULT_PARENT / "Blogs_rows.csv"))
LIFE_BLOGS_CSV = Path(os.environ.get("LIFE_BLOGS_CSV", DEFAULT_PARENT / "life_blogs_rows.csv"))
PROJECTS_CSV = Path(os.environ.get("PROJECTS_CSV", DEFAULT_PARENT / "Projects_rows.csv"))


# -------- index definition (mirrors OpenSearchIndexClient mappings) -----------

INDEX_SETTINGS = {
    "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 1,
    },
    "mappings": {
        "properties": {
            "source_type":  {"type": "keyword"},
            "source_id":    {"type": "keyword"},
            "title":        {"type": "text", "analyzer": "english"},
            "summary":      {"type": "text", "analyzer": "english"},
            "content":      {"type": "text", "analyzer": "english"},
            "category":     {"type": "keyword"},
            "tags":         {"type": "keyword"},
            "url":          {"type": "keyword"},
            "image_url":    {"type": "keyword"},
            "visibility":   {"type": "keyword"},
            "published_at": {"type": "date"},
            "updated_at":   {"type": "date"},
        }
    },
}


# -------- helpers ------------------------------------------------------------

_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")


def strip_html(s: str | None) -> str:
    if not s:
        return ""
    s = html.unescape(s)
    s = _TAG_RE.sub(" ", s)
    s = _WS_RE.sub(" ", s).strip()
    return s


def parse_tags(raw: str | None) -> list[str]:
    if not raw:
        return []
    parts = re.split(r"[,;|]", raw)
    return [p.strip() for p in parts if p and p.strip()]


def nz(s: str | None) -> str | None:
    if s is None:
        return None
    s = s.strip()
    return s or None


# Accept the variants we actually see in the Supabase CSV exports:
#   "March 10, 2024"           (Blogs.date)
#   "2025-05-29"               (life_blogs.published_at)
#   "2025-05-30 05:53:49+00"   (life_blogs.updated_at — Postgres timestamptz)
#   "2024-03-10 17:44:27"      (Projects.published_at — naive timestamp)
#   "2024-03-10T17:44:27Z"     (ISO; just in case)
_DATE_FORMATS: tuple[str, ...] = (
    "%B %d, %Y",
    "%b %d, %Y",
    "%Y-%m-%d",
    "%Y-%m-%d %H:%M:%S%z",
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%dT%H:%M:%S%z",
    "%Y-%m-%dT%H:%M:%SZ",
    "%Y-%m-%dT%H:%M:%S",
)


def to_iso_date(raw: str | None) -> str | None:
    """Normalize the messy date strings in the CSVs to ISO-8601 UTC.
    Returns None if the value is missing or unparseable (we just drop it)."""
    s = nz(raw)
    if not s:
        return None
    # Postgres timestamptz often prints '+00' instead of '+0000'/'+00:00'.
    candidate = s
    if re.search(r"[+\-]\d{2}$", candidate):
        candidate = candidate + "00"
    for fmt in _DATE_FORMATS:
        try:
            dt = datetime.strptime(candidate, fmt)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        except ValueError:
            continue
    return None


# -------- per-source normalizers ---------------------------------------------

def docs_from_blogs(path: Path) -> Iterable[dict]:
    if not path.exists():
        print(f"  ⚠️  {path} not found, skipping BLOG.")
        return
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            source_id = nz(row.get("id"))
            if not source_id:
                continue
            yield {
                "_id": f"BLOG:{source_id}",
                "source_type": "BLOG",
                "source_id": source_id,
                "title": nz(row.get("title")) or "",
                "summary": nz(row.get("description")) or "",
                "content": strip_html(row.get("content")),
                "category": nz(row.get("category")),
                "tags": parse_tags(row.get("tags")),
                "url": f"/blog-single/{source_id}",
                "image_url": nz(row.get("image_url")),
                "visibility": "PUBLIC",
                "published_at": to_iso_date(row.get("date")),
            }


def docs_from_life_blogs(path: Path) -> Iterable[dict]:
    if not path.exists():
        print(f"  ⚠️  {path} not found, skipping LIFE_BLOG.")
        return
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            source_id = nz(row.get("id"))
            if not source_id:
                continue
            require_login = (row.get("require_login") or "").strip().lower() in ("true", "t", "1", "yes")
            yield {
                "_id": f"LIFE_BLOG:{source_id}",
                "source_type": "LIFE_BLOG",
                "source_id": source_id,
                "title": nz(row.get("title")) or "",
                "summary": nz(row.get("description")) or "",
                "content": strip_html(row.get("content")),
                "category": nz(row.get("category")),
                "tags": parse_tags(row.get("tags")),
                "url": f"/life-blog/{source_id}",
                "image_url": nz(row.get("image_url")),
                "visibility": "PRIVATE" if require_login else "PUBLIC",
                "published_at": to_iso_date(row.get("published_at")),
                "updated_at": to_iso_date(row.get("updated_at")),
            }


def docs_from_projects(path: Path) -> Iterable[dict]:
    if not path.exists():
        print(f"  ⚠️  {path} not found, skipping PROJECT.")
        return
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            source_id = nz(row.get("id"))
            if not source_id:
                continue
            tech = parse_tags(row.get("technology"))
            yield {
                "_id": f"PROJECT:{source_id}",
                "source_type": "PROJECT",
                "source_id": source_id,
                "title": nz(row.get("title")) or "",
                "summary": "",  # projects have no separate summary column
                "content": strip_html(row.get("content")),
                "category": nz(row.get("category")),
                "tags": tech,                       # technologies → tags
                "url": f"/work-single/{source_id}",
                "image_url": nz(row.get("image_url")),
                # external URL (project demo) goes to its own field
                "external_url": nz(row.get("URL")),
                "visibility": "PUBLIC",
                "published_at": to_iso_date(row.get("published_at")),
                "updated_at": to_iso_date(row.get("updated_at")),
            }


# -------- OpenSearch ops -----------------------------------------------------

def open_client() -> OpenSearch:
    return OpenSearch(
        hosts=[{"host": HOST, "port": PORT}],
        http_auth=(USERNAME, PASSWORD),
        use_ssl=True,
        verify_certs=True,
        ssl_show_warn=False,
        timeout=30,
        max_retries=3,
        retry_on_timeout=True,
    )


def ensure_index(client: OpenSearch, name: str) -> None:
    if client.indices.exists(index=name):
        print(f"  index '{name}' already exists — leaving mappings as-is")
        return
    client.indices.create(index=name, body=INDEX_SETTINGS)
    print(f"  created index '{name}'")


def swap_alias(client: OpenSearch, alias: str, new_index: str) -> None:
    """Atomically point `alias` at `new_index`, removing it from any other index."""
    actions: list[dict] = []
    try:
        existing = client.indices.get_alias(name=alias)
        for idx in existing.keys():
            if idx != new_index:
                actions.append({"remove": {"index": idx, "alias": alias}})
    except Exception:
        pass  # alias does not exist yet
    actions.append({"add": {"index": new_index, "alias": alias}})
    client.indices.update_aliases(body={"actions": actions})
    print(f"  alias '{alias}' → '{new_index}'   (actions: {len(actions)})")


# -------- main ---------------------------------------------------------------

def main() -> int:
    print(f"OpenSearch backfill → {HOST}:{PORT}")
    client = open_client()
    info = client.info()
    print(f"  connected: {info.get('cluster_name')} v{info['version']['number']}")

    ensure_index(client, INDEX_VERSION)

    sources = [
        ("BLOG",      list(docs_from_blogs(BLOGS_CSV))),
        ("LIFE_BLOG", list(docs_from_life_blogs(LIFE_BLOGS_CSV))),
        ("PROJECT",   list(docs_from_projects(PROJECTS_CSV))),
    ]

    total_ok = total_err = 0
    for label, docs in sources:
        if not docs:
            print(f"{label:10s}  (no rows)")
            continue
        actions = ({"_op_type": "index", "_index": INDEX_VERSION, **d} for d in docs)
        ok, errors = helpers.bulk(client, actions, raise_on_error=False, stats_only=False)
        err_count = len(errors) if isinstance(errors, list) else 0
        total_ok += ok
        total_err += err_count
        print(f"{label:10s}  indexed={ok}  errors={err_count}")
        for e in (errors[:3] if isinstance(errors, list) else []):
            print(f"   ↳ {e}")

    # refresh and report
    client.indices.refresh(index=INDEX_VERSION)
    count = client.count(index=INDEX_VERSION)["count"]
    print(f"index '{INDEX_VERSION}' now contains {count} document(s)")

    # finally, point the alias
    swap_alias(client, ALIAS, INDEX_VERSION)

    print(f"\nDone. total_ok={total_ok} total_err={total_err}")
    return 0 if total_err == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
