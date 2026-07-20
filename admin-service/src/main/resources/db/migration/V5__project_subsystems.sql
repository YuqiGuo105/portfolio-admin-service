CREATE TABLE IF NOT EXISTS public.project_subsystems (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id          uuid NOT NULL REFERENCES public."Projects"(id),
    linked_project_id   uuid REFERENCES public."Projects"(id) ON DELETE SET NULL,
    slug                text NOT NULL,
    title               text NOT NULL,
    eyebrow             text,
    summary             text NOT NULL,
    design_intent       text NOT NULL,
    maturity            text NOT NULL,
    sort_order          int NOT NULL DEFAULT 0,
    diagram_config      jsonb NOT NULL,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT project_subsystems_slug_unique UNIQUE (project_id, slug),
    CONSTRAINT project_subsystems_maturity_check
        CHECK (maturity IN ('BUILT', 'SYSTEM_DESIGN'))
);

CREATE INDEX IF NOT EXISTS idx_project_subsystems_public
    ON public.project_subsystems (project_id, active, sort_order, created_at);

COMMENT ON TABLE public.project_subsystems IS
    'Database-owned interactive architecture views rendered inside a parent project.';

COMMENT ON COLUMN public.project_subsystems.diagram_config IS
    'Validated nodes, edges, domains and request trace routes consumed by the generic frontend renderer.';
