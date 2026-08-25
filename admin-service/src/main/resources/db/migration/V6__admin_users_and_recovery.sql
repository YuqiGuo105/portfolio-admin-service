-- V6: Admin user registry and recovery-friendly indexes.
-- The env allow-list remains a break-glass fallback, but runtime admin access
-- can now be managed from data instead of redeploying config.

CREATE TABLE IF NOT EXISTS public.admin_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL,
    role text NOT NULL DEFAULT 'VIEWER',
    status text NOT NULL DEFAULT 'ACTIVE',
    display_name text,
    note text,
    created_by text,
    updated_by text,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_admin_users_role
        CHECK (role IN ('VIEWER', 'EDITOR', 'PUBLISHER', 'ADMIN')),
    CONSTRAINT chk_admin_users_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_users_email_lower
    ON public.admin_users (lower(email));

CREATE INDEX IF NOT EXISTS idx_admin_users_status_role
    ON public.admin_users (status, role, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_content_event_outbox_recovery
    ON public.content_event_outbox (status, updated_at DESC)
    WHERE status IN ('FAILED', 'DLQ', 'PROCESSING');

CREATE INDEX IF NOT EXISTS idx_indexing_jobs_recovery
    ON public.indexing_jobs (status, updated_at DESC)
    WHERE status IN ('FAILED', 'SKIPPED', 'PROCESSING');
