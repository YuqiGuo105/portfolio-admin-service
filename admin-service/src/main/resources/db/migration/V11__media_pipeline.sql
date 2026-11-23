-- Media pipeline: upload -> transcode -> publish.
--
-- One "video and task database", three tables:
--   media_assets        one row per uploaded image or video, and its publish pointer
--   media_jobs          every unit of work the Media Service runs, with a lease
--   media_event_outbox  events the Content Service still owes to Kafka
-- plus media_public, the only thing the public site may read.
--
-- Written by the Content Service (upload API, outbox) and the Media Service
-- (jobs, publish pointer). Poison Kafka records reuse indexing_kafka_quarantine.

create table if not exists media_assets (
    id uuid primary key,
    kind varchar(16) not null,
    status varchar(16) not null,
    title varchar(300),
    original_filename varchar(300) not null,
    content_type varchar(128) not null,
    size_bytes bigint not null,

    -- The open upload session. Replaced by a new session, cleared on completion.
    upload_id varchar(64),
    storage_upload_id text,
    upload_key text,
    part_size_bytes bigint,
    part_count integer,
    upload_expires_at timestamptz,

    -- The bound source. Immutable once source_version is set for it.
    source_version integer not null default 0,
    source_key text,
    source_etag varchar(200),
    source_bytes bigint,

    profile_version varchar(32),
    processing_started_at timestamptz,
    probe jsonb,

    -- Publish pointer. Visitors only ever see what this points at.
    published_source_version integer,
    published_profile_version varchar(32),
    manifest jsonb,
    published_at timestamptz,

    failure_code varchar(64),
    failure_message text,

    created_by varchar(500) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    check (kind in ('VIDEO','IMAGE')),
    check (status in ('UPLOADING','PROCESSING','READY','FAILED')),
    check (size_bytes > 0),
    check (source_version >= 0)
);
create index if not exists media_assets_status_updated on media_assets(status, updated_at);
create index if not exists media_assets_created on media_assets(created_at desc);
alter table media_assets enable row level security;
revoke all on media_assets from anon, authenticated;

create table if not exists media_jobs (
    id uuid primary key,
    asset_id uuid not null references media_assets(id) on delete cascade,
    source_version integer not null,
    profile_version varchar(32) not null,
    job_type varchar(24) not null,
    rendition varchar(32) not null,
    spec jsonb not null default '{}'::jsonb,
    status varchar(16) not null default 'PENDING',
    attempt integer not null default 0,
    max_attempts integer not null default 3,
    lease_owner varchar(128),
    lease_expires_at timestamptz,
    next_attempt_at timestamptz not null default now(),
    output jsonb,
    last_error text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    -- A replayed Kafka message tries to create the same work again; this stops it.
    unique (asset_id, source_version, profile_version, rendition),
    check (job_type in ('PLAN','VIDEO_RENDITION','VIDEO_MP4','VIDEO_POSTER','IMAGE_VARIANTS','PUBLISH')),
    check (status in ('PENDING','RUNNING','SUCCEEDED','DEAD')),
    check (attempt >= 0 and max_attempts >= 1)
);
create index if not exists media_jobs_claimable on media_jobs(next_attempt_at, created_at) where status = 'PENDING';
create index if not exists media_jobs_leases on media_jobs(lease_expires_at) where status = 'RUNNING';
alter table media_jobs enable row level security;
revoke all on media_jobs from anon, authenticated;

create table if not exists media_event_outbox (
    id uuid primary key,
    event_id varchar(64) not null unique,
    event_type varchar(64) not null,
    asset_id uuid not null references media_assets(id) on delete cascade,
    source_version integer not null,
    partition_key varchar(128) not null,
    payload jsonb not null,
    status varchar(16) not null default 'PENDING',
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    lease_expires_at timestamptz,
    last_error text,
    created_at timestamptz not null default now(),
    sent_at timestamptz,

    check (status in ('PENDING','PROCESSING','SENT','DLQ'))
);
create index if not exists media_event_outbox_ready on media_event_outbox(next_attempt_at) where status in ('PENDING','PROCESSING');
alter table media_event_outbox enable row level security;
revoke all on media_event_outbox from anon, authenticated;

-- The public read model: published assets only, and only what a player needs.
-- The view runs with its owner's rights, so anon can read it while the tables
-- above stay closed.
create or replace view media_public as
select id,
       kind,
       manifest,
       published_at
  from media_assets
 where status = 'READY'
   and manifest is not null;
grant select on media_public to anon, authenticated;
