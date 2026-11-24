-- Original deletion never changes the asset's public publish pointer or its usages.
alter table media_assets add column original_status varchar(16) not null default 'AVAILABLE';
alter table media_assets add column original_deleted_at timestamptz;
-- Deliberately no legacy backfill from today's configuration: that would invent upload ownership.
alter table media_assets add column original_storage_endpoint text;
alter table media_assets add column original_storage_bucket text;
alter table media_assets add column original_storage_generation text;
alter table media_assets add constraint media_original_status_valid
    check (original_status in ('AVAILABLE', 'DELETING', 'DELETE_FAILED', 'DELETED'));
alter table media_assets add constraint media_original_deletion_terminal_video
    check (original_status = 'AVAILABLE' or (kind = 'VIDEO' and status = 'READY'));
alter table media_assets add constraint media_original_deleted_time
    check ((original_status = 'DELETED') = (original_deleted_at is not null));
alter table media_assets add constraint media_original_deletion_not_tombstoned
    check (original_status not in ('DELETING', 'DELETE_FAILED') or deleted_at is null);

-- Committed before storage deletion. A retry always targets this exact endpoint, bucket, key and generation.
create table media_original_deletions (
    asset_id uuid primary key references media_assets(id),
    source_version integer not null,
    source_key text not null,
    storage_bucket text not null,
    storage_endpoint text not null,
    storage_generation text not null check (storage_generation ~ '^[1-9][0-9]*$'),
    expected_metadata_version integer not null check (expected_metadata_version >= 0),
    requested_by varchar(500) not null,
    requested_at timestamptz not null default now(),
    completed_at timestamptz
);
alter table media_original_deletions enable row level security;
revoke all on media_original_deletions from public, anon, authenticated;

-- Serialize new/reopened work with deletion, including older worker binaries. Existing active
-- jobs already block deletion; ordinary heartbeats/claims do not acquire a second asset lock.
create function media_original_job_guard() returns trigger language plpgsql as $$
begin
    if new.status in ('PENDING', 'RUNNING') then
        if TG_OP = 'UPDATE' then
            if old.status in ('PENDING', 'RUNNING') and old.asset_id = new.asset_id then
                return new;
            end if;
        end if;
        perform 1 from media_assets where id = new.asset_id and original_status = 'AVAILABLE' for update;
        if not found then return null; end if;
    end if;
    return new;
end;
$$;
create trigger media_original_job_guard before insert or update of status, asset_id on media_jobs
    for each row execute function media_original_job_guard();
