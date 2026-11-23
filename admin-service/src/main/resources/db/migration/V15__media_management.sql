-- Metadata edits are independent of source/transcode versions. Deletion is a tombstone,
-- not a promise to erase private originals or already-cached public renditions.
alter table media_assets add column description varchar(2000) not null default '';
alter table media_assets add column metadata_version integer not null default 0;
alter table media_assets add column deleted_at timestamptz;
alter table media_assets add constraint media_metadata_version_nonnegative check (metadata_version >= 0);

-- Preserve the existing, deliberately narrow public projections and read-only grants.
create or replace view media_public as
select id, kind, manifest, published_at from media_assets
where status = 'READY' and manifest is not null and deleted_at is null;
create or replace view media_demo_catalog as
select p.placement_key, p.asset_id, p.presentation, a.manifest
from media_presentations p join media_assets a on a.id = p.asset_id
where p.enabled = true and a.status = 'READY' and a.manifest is not null and a.deleted_at is null;
revoke all on media_public, media_demo_catalog from public, anon, authenticated;
grant select on media_public, media_demo_catalog to anon, authenticated;
