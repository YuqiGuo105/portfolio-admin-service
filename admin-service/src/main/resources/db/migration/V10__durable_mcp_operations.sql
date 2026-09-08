create table if not exists mcp_operations (
    operation_id uuid primary key,
    principal varchar(320) not null,
    tool varchar(200) not null,
    idempotency_key varchar(200) not null,
    request_hash varchar(64) not null,
    state varchar(32) not null,
    attempt integer not null default 1,
    lease_token uuid not null,
    lease_until timestamptz not null,
    response_json text,
    http_status integer,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (principal, tool, idempotency_key),
    check (state in ('RUNNING','SUCCEEDED','FAILED_FINAL','RETRYABLE','UNKNOWN'))
);
create index if not exists mcp_operations_principal_created on mcp_operations(principal, created_at desc);
alter table mcp_operations enable row level security;
revoke all on mcp_operations from anon, authenticated;

create table if not exists mcp_operation_transitions (
    id bigserial primary key,
    operation_id uuid not null references mcp_operations(operation_id),
    state varchar(32) not null,
    attempt integer not null,
    occurred_at timestamptz not null default now()
);
create index if not exists mcp_operation_transitions_operation on mcp_operation_transitions(operation_id, id);
alter table mcp_operation_transitions enable row level security;
revoke all on mcp_operation_transitions from anon, authenticated;

create table if not exists indexing_kafka_quarantine (
    worker varchar(32) not null,
    topic varchar(255) not null,
    partition_id integer not null,
    record_offset bigint not null,
    payload text,
    error_type varchar(255),
    created_at timestamptz not null default now(),
    primary key(worker,topic,partition_id,record_offset)
);
alter table indexing_kafka_quarantine enable row level security;
revoke all on indexing_kafka_quarantine from anon, authenticated;

create table if not exists operation_event_journal (
    event_id varchar(64) primary key,
    payload text not null,
    created_at timestamptz not null default now(),
    projected_at timestamptz,
    attempts integer not null default 0,
    next_retry_at timestamptz not null default now()
);
create index if not exists operation_journal_pending on operation_event_journal(next_retry_at) where projected_at is null;
alter table operation_event_journal enable row level security;
revoke all on operation_event_journal from anon, authenticated;
