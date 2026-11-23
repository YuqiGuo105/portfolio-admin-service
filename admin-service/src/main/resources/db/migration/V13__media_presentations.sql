-- Only explicitly attached, completed assets enter the public demo catalog.
create table if not exists media_presentations (
    placement_key varchar(160) primary key,
    asset_id uuid not null references media_assets(id),
    presentation jsonb not null,
    enabled boolean not null default false,
    updated_at timestamptz not null default now(),
    check (jsonb_typeof(presentation) = 'object')
);
create index if not exists media_presentations_asset on media_presentations(asset_id);
alter table media_presentations enable row level security;
revoke all on media_presentations from anon, authenticated;

-- Intentionally owner-evaluated public projection: never exposes sources, jobs, or credentials.
create or replace view media_demo_catalog as
select p.placement_key, p.asset_id, p.presentation, a.manifest
from media_presentations p join media_assets a on a.id = p.asset_id
where p.enabled = true and a.status = 'READY' and a.manifest is not null;
grant select on media_demo_catalog to anon, authenticated;
