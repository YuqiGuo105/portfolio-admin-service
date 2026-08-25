-- Convergent migration for environments where an older migration already used
-- V6. Do not rely on V6 having created this table: make the desired schema
-- available first, then tighten its authorization constraints.
CREATE TABLE IF NOT EXISTS public.admin_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL,
    role text NOT NULL DEFAULT 'EDITOR',
    status text NOT NULL DEFAULT 'ACTIVE',
    display_name text,
    note text,
    created_by text,
    updated_by text,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_admin_users_role
        CHECK (role IN ('EDITOR', 'PUBLISHER', 'ADMIN')),
    CONSTRAINT chk_admin_users_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_users_email_lower
    ON public.admin_users (lower(email));

CREATE INDEX IF NOT EXISTS idx_admin_users_status_role
    ON public.admin_users (status, role, created_at DESC);

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
