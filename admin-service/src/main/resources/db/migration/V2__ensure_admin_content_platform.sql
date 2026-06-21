-- V2: Ensure the V1 admin content platform tables actually exist.
--
-- Background: V1__admin_content_platform.sql was never executed on the
-- production Supabase database. baseline-on-migrate=true plus a non-empty
-- public schema (the legacy "Blogs"/"Projects"/life_blogs tables were already
-- in place) caused Flyway to create a BASELINE row for version 1 instead of
-- running V1's CREATE TABLE statements. From that point on Flyway reported
-- "Schema public is up to date. No migration necessary." and the admin
-- platform queries failed with:
--     ERROR: relation "content_versions" does not exist
--
-- We can't safely delete the V1 baseline row from flyway_schema_history at
-- runtime, so this migration re-applies the V1 DDL idempotently. Every
-- statement uses IF NOT EXISTS / re-settable defaults, so it is a no-op on
-- environments where V1 actually ran (e.g. fresh local DBs).

-- 1. Add default UUID generation to "Blogs" if not already set.
ALTER TABLE public."Blogs"
    ALTER COLUMN id SET DEFAULT gen_random_uuid();

-- 2. kb_documents metadata indexes.
CREATE INDEX IF NOT EXISTS kb_documents_metadata_gin_idx
    ON public.kb_documents USING gin (metadata);

CREATE INDEX IF NOT EXISTS kb_documents_source_idx
    ON public.kb_documents (
        ((metadata->>'source_type')),
        ((metadata->>'source_id')),
        ((metadata->>'source_version'))
    );

-- 3. content_versions: every publish action snapshots the content.
CREATE TABLE IF NOT EXISTS public.content_versions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    source_type     text NOT NULL,
    source_id_text  text NOT NULL,

    version         int  NOT NULL,

    title           text,
    summary         text,
    content         text,
    category        text,
    tags            text,

    snapshot        jsonb NOT NULL DEFAULT '{}'::jsonb,

    change_note     text,

    created_at      timestamptz DEFAULT now(),
    created_by      text,

    CONSTRAINT content_versions_unique UNIQUE (source_type, source_id_text, version)
);

-- 4. content_event_outbox: events to be published to Kafka (or any broker).
CREATE TABLE IF NOT EXISTS public.content_event_outbox (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    event_type      text NOT NULL,

    source_type     text NOT NULL,
    source_id_text  text NOT NULL,
    source_version  int  NOT NULL,

    topic           text NOT NULL,

    payload         jsonb NOT NULL,

    status          text NOT NULL DEFAULT 'PENDING',

    retry_count     int NOT NULL DEFAULT 0,
    next_retry_at   timestamptz DEFAULT now(),

    idempotency_key text NOT NULL UNIQUE,

    last_error      text,

    created_at      timestamptz DEFAULT now(),
    sent_at         timestamptz,
    updated_at      timestamptz DEFAULT now()
);

-- 5. indexing_jobs: deferred RAG and search indexing work.
CREATE TABLE IF NOT EXISTS public.indexing_jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    source_type     text NOT NULL,
    source_id_text  text NOT NULL,
    source_version  int  NOT NULL,

    job_type        text NOT NULL,

    status          text NOT NULL DEFAULT 'PENDING',

    retry_count     int NOT NULL DEFAULT 0,
    next_retry_at   timestamptz DEFAULT now(),

    started_at      timestamptz,
    completed_at    timestamptz,

    last_error      text,

    idempotency_key text NOT NULL UNIQUE,

    created_at      timestamptz DEFAULT now(),
    updated_at      timestamptz DEFAULT now()
);

-- 6. content_admin_audit_logs: every admin mutation produces an audit entry.
CREATE TABLE IF NOT EXISTS public.content_admin_audit_logs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    actor           text,
    action          text NOT NULL,

    source_type     text NOT NULL,
    source_id_text  text NOT NULL,
    source_version  int,

    before_snapshot jsonb,
    after_snapshot  jsonb,

    created_at      timestamptz DEFAULT now()
);

-- 7. Supporting indexes.
CREATE INDEX IF NOT EXISTS idx_content_versions_source
    ON public.content_versions (source_type, source_id_text, version DESC);

CREATE INDEX IF NOT EXISTS idx_content_event_outbox_status
    ON public.content_event_outbox (status, next_retry_at, created_at);

CREATE INDEX IF NOT EXISTS idx_indexing_jobs_source
    ON public.indexing_jobs (source_type, source_id_text, source_version);

CREATE INDEX IF NOT EXISTS idx_indexing_jobs_status
    ON public.indexing_jobs (status, job_type, next_retry_at, created_at);

CREATE INDEX IF NOT EXISTS idx_content_admin_audit_logs_source
    ON public.content_admin_audit_logs (source_type, source_id_text, created_at DESC);
