alter table media_event_outbox
    add column wake_status text not null default 'PENDING'
        check (wake_status in ('PENDING', 'PROCESSING', 'DONE', 'DLQ')),
    add column wake_attempts integer not null default 0,
    add column wake_after timestamptz not null default now(),
    add column wake_lease_until timestamptz,
    add column wake_token uuid,
    add column wake_error text;

create index media_outbox_wake_due on media_event_outbox (wake_after)
    where status = 'SENT' and wake_status in ('PENDING', 'PROCESSING');
