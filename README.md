# portfolio-admin-service

Staff-level **Admin Content Platform** backend for the Portfolio site.

This service sits next to [`portfolio-notification-service`](https://github.com/YuqiGuo105/portfolio-notification-service)
and the Next.js Portfolio. It owns admin write workflows for the four content
sources (Blogs, Projects, life_blogs, experience) and produces:

- **`content_versions`** — immutable snapshots written on every publish.
- **`content_event_outbox`** — transactional outbox; a future Kafka producer
  worker picks rows up and publishes to the notification topics.
- **`indexing_jobs`** — deferred RAG and Search index work, processed by
  workers (RAG worker embeds into `kb_documents`; SEARCH worker upserts into
  OpenSearch / Elasticsearch).
- **`content_admin_audit_logs`** — every admin mutation is recorded.

The existing Supabase tables remain the **source of truth**. Adapters per
source type normalize their inconsistent shape into `NormalizedContent`.

---

## Architecture

```
                     ┌───────────────────┐
                     │  Admin UI / curl  │
                     └─────────┬─────────┘
                               │ X-Admin-Secret  OR  Bearer Supabase JWT
                               ▼
                ┌──────────────────────────────────┐
                │   AdminAuthFilter (security)     │
                └──────────────┬───────────────────┘
                               ▼
        ┌──────────────────────────────────────────────────┐
        │  Controllers:                                    │
        │    /api/admin/content/**                         │
        │    /api/admin/indexing-jobs/**                   │
        │    /api/admin/outbox-events                      │
        └──────────────┬───────────────────────────────────┘
                       ▼
              ┌─────────────────────────┐
              │     ContentService       │  (orchestrator)
              └─┬────────┬──────────┬───┘
                │        │          │
                ▼        ▼          ▼
       VersionSvc  OutboxSvc  IndexingJobSvc  AuditLogSvc
                │        │          │
                ▼        ▼          ▼
            content_  content_  indexing_jobs  content_admin_
            versions  event_                   audit_logs
                      outbox
                       │
                       ▼  (background, NOT in this service yet)
                  ┌──────────┐    ┌────────────┐    ┌────────────┐
                  │ Kafka    │    │ RAG worker │    │ Search     │
                  │ producer │    │ → kb_docs  │    │ worker →   │
                  │ worker   │    │   embeds   │    │ OpenSearch │
                  └──────────┘    └────────────┘    └────────────┘
```

### Source adapters

| SourceType  | Table              | URL                       | Topic            |
|-------------|--------------------|---------------------------|------------------|
| `BLOG`      | `public."Blogs"`   | `/blog-single/{id}`       | `ARTICLE_UPDATES`|
| `LIFE_BLOG` | `public.life_blogs`| `/life-blog/{id}`         | `ARTICLE_UPDATES`|
| `PROJECT`   | `public."Projects"`| `/work-single/{id}`       | `FEATURE_UPDATES`|
| `EXPERIENCE`| `public.experience`| `/#resume`                | `JOB_UPDATES`    |

### What this service does NOT do

- ❌ Generate embeddings synchronously
- ❌ Call OpenSearch / Elasticsearch synchronously
- ❌ Publish to Kafka synchronously
- ❌ Render admin UI (Next.js Portfolio does that)

Workers consume `indexing_jobs` and `content_event_outbox` asynchronously.

---

## Endpoints

All endpoints require auth: **`X-Admin-Secret: <secret>`** _or_
**`Authorization: Bearer <Supabase JWT>`** (with `email` in
`ADMIN_ALLOWED_EMAILS`).

| Method | Path                                                  | Purpose |
|--------|-------------------------------------------------------|---------|
| GET    | `/api/admin/content`                                  | List across one or all sources (`?type=BLOG&keyword=...&category=...`) |
| GET    | `/api/admin/content/{sourceType}/{sourceId}`          | Detail + latest version + recent jobs + audit logs |
| POST   | `/api/admin/content/{sourceType}`                     | Create (`{ data: {...}, publish?: true, changeNote? }`) |
| PUT    | `/api/admin/content/{sourceType}/{sourceId}`          | Update (same body shape as create) |
| POST   | `/api/admin/content/{sourceType}/{sourceId}/publish`  | Publish (snapshot + outbox + RAG/SEARCH jobs + audit) |
| POST   | `/api/admin/content/{sourceType}/{sourceId}/reindex-rag`    | Manual RAG re-index |
| POST   | `/api/admin/content/{sourceType}/{sourceId}/reindex-search` | Manual Search re-index |
| GET    | `/api/admin/indexing-jobs`                            | List jobs (`?status=FAILED&jobType=RAG_INDEX`) |
| POST   | `/api/admin/indexing-jobs/{jobId}/retry`              | Retry FAILED or SKIPPED job |
| GET    | `/api/admin/outbox-events`                            | List outbox events for debugging |
| GET    | `/api/health`                                         | Liveness, no auth |
| GET    | `/swagger-ui.html`                                    | Interactive API docs (no auth on docs themselves) |

---

## Configuration

| Env var                    | Purpose                                                 |
|----------------------------|---------------------------------------------------------|
| `SPRING_DATASOURCE_URL`    | JDBC URL to the Supabase Postgres                       |
| `SPRING_DATASOURCE_USERNAME` | usually `postgres`                                    |
| `SPRING_DATASOURCE_PASSWORD` | DB password                                           |
| `ADMIN_SECRET`             | MVP admin auth: header value clients must send         |
| `SUPABASE_JWT_SECRET`      | Supabase project JWT secret (base64 or raw)            |
| `ADMIN_ALLOWED_EMAILS`     | Comma-separated allow-list for Supabase JWT path       |
| `ALLOWED_ORIGINS`          | CORS origins (default `http://localhost:3000`)         |
| `PORT`                     | HTTP port (default 8081)                                |

---

## Manual verification

After deploying the service against a Supabase that has the migration applied:

```bash
# 1. Health
curl -s http://localhost:8081/api/health

# 2. Auth required
curl -s -i http://localhost:8081/api/admin/content   # → 401

# 3. List
curl -s -H "X-Admin-Secret: $ADMIN_SECRET" \
  "http://localhost:8081/api/admin/content?type=BLOG&limit=5" | jq .

# 4. Create a BLOG and immediately publish
curl -s -X POST -H "X-Admin-Secret: $ADMIN_SECRET" -H "Content-Type: application/json" \
  -d '{"data":{"title":"Hello","summary":"Demo","content":"...","category":"Tech","tags":["demo"]},"publish":true}' \
  http://localhost:8081/api/admin/content/BLOG | jq .

# 5. Verify version + outbox + indexing jobs exist
SID=<id-from-step-4>
curl -s -H "X-Admin-Secret: $ADMIN_SECRET" \
  "http://localhost:8081/api/admin/content/BLOG/$SID" | jq '.latestVersion, .recentIndexingJobs, .recentAuditLogs[0]'

curl -s -H "X-Admin-Secret: $ADMIN_SECRET" \
  "http://localhost:8081/api/admin/outbox-events?limit=5" | jq '.items[0]'

curl -s -H "X-Admin-Secret: $ADMIN_SECRET" \
  "http://localhost:8081/api/admin/indexing-jobs?limit=5" | jq '.items[].jobType'

# 6. Retry a (manually FAILED) indexing job
curl -s -X POST -H "X-Admin-Secret: $ADMIN_SECRET" \
  http://localhost:8081/api/admin/indexing-jobs/$JOB_ID/retry | jq .
```

Expected:

- One row in `public.content_versions` (`version=1` after publish).
- One row in `public.content_event_outbox` with `idempotency_key = CONTENT_PUBLISHED:BLOG:<id>:v1`.
- Two rows in `public.indexing_jobs` (`RAG_INDEX`, `SEARCH_INDEX`), both `status=PENDING`.
- Two rows in `public.content_admin_audit_logs` (`CREATE`, then `PUBLISH`).
- Listing content shows `latestVersion=1`, `ragStatus=PENDING`, `searchStatus=PENDING`.

---

## Local development

```bash
# 1. Apply Flyway migration (runs automatically on boot)
export SPRING_DATASOURCE_URL='jdbc:postgresql://db.<ref>.supabase.co:5432/postgres?sslmode=require'
export SPRING_DATASOURCE_USERNAME='postgres'
export SPRING_DATASOURCE_PASSWORD='...'
export ADMIN_SECRET='dev-admin-secret-change-me'

mvn spring-boot:run
```

Swagger UI: <http://localhost:8081/swagger-ui.html>

---

## Notes for future work

- Replace `ADMIN_ALLOWED_EMAILS` with a Supabase `admin_users` table.
- Add a worker module (or reuse `portfolio-notification-service`) to drain
  `content_event_outbox` to Kafka.
- Add a RAG worker that consumes `indexing_jobs` (`RAG_INDEX`), chunks text,
  embeds, and writes `kb_documents` using the metadata shape:
  ```json
  {
    "source_type": "BLOG",
    "source_id": "uuid",
    "source_version": 3,
    "chunk_index": 0,
    "content_hash": "sha256",
    "title": "...",
    "url": "/blog-single/uuid",
    "status": "ACTIVE"
  }
  ```
- Add a Search worker that consumes `SEARCH_INDEX` and calls a
  `SearchIndexClient` implementation against OpenSearch / Elasticsearch.
