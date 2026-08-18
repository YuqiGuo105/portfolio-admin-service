alter table content_event_outbox
    add column if not exists version bigint not null default 0;

alter table indexing_jobs
    add column if not exists version bigint not null default 0;

create index if not exists idx_outbox_ready_claim
    on content_event_outbox (next_retry_at, created_at)
    where status in ('PENDING', 'FAILED', 'PROCESSING');

create index if not exists idx_indexing_jobs_ready_claim
    on indexing_jobs (next_retry_at, created_at)
    where status in ('PENDING', 'FAILED', 'PROCESSING');
