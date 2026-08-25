-- VIEWER is not an administrator identity. Preserve any historical rows for
-- audit purposes, but suspend them instead of silently granting write access.
UPDATE public.admin_users
SET role = 'EDITOR',
    status = 'SUSPENDED',
    note = concat_ws(' | ', nullif(note, ''), 'Suspended during VIEWER role removal'),
    updated_at = now()
WHERE role = 'VIEWER';

ALTER TABLE public.admin_users
    ALTER COLUMN role SET DEFAULT 'EDITOR';

ALTER TABLE public.admin_users
    DROP CONSTRAINT IF EXISTS chk_admin_users_role;

ALTER TABLE public.admin_users
    ADD CONSTRAINT chk_admin_users_role
        CHECK (role IN ('EDITOR', 'PUBLISHER', 'ADMIN'));
