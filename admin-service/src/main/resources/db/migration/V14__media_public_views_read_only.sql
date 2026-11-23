-- Supabase default grants also apply to views. Public projections are read-only.
revoke all on media_public, media_demo_catalog from public, anon, authenticated;
grant select on media_public, media_demo_catalog to anon, authenticated;
