-- An authenticated browser supplies the bytes; the existing media recovery worker completes
-- the approved placement change even if the browser or external AI conversation disconnects.
create table media_presentation_jobs (
    id uuid primary key,
    placement_key varchar(80) not null check (placement_key = 'yuqi-intro'),
    status varchar(32) not null check (status in
        ('WAITING_UPLOAD','WAITING_MEDIA','SUCCEEDED','FAILED','CONFLICT','CANCELLED','EXPIRED')),
    expected_revision char(64) not null,
    previous_asset_id uuid,
    asset_id uuid references media_assets(id),
    title varchar(300) not null,
    created_by varchar(500) not null,
    approved_by varchar(320),
    result_revision char(64),
    error_code varchar(80),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    expires_at timestamptz not null default now() + interval '24 hours'
);
create index media_presentation_jobs_pending on media_presentation_jobs(created_at)
    where status in ('WAITING_UPLOAD','WAITING_MEDIA');
create index media_presentation_jobs_recent on media_presentation_jobs(created_at desc);
alter table media_presentation_jobs enable row level security;
revoke all on media_presentation_jobs from public, anon, authenticated;
