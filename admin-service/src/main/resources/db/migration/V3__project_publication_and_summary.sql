-- Project drafts use published_at IS NULL. Published projects receive the
-- timestamp only when ContentService executes the publish lifecycle.
--
-- summary is intentionally separate from content so notification previews and
-- search result snippets never reuse an entire project article.

ALTER TABLE public."Projects"
    ADD COLUMN IF NOT EXISTS summary text,
    ADD COLUMN IF NOT EXISTS publication_status varchar(16),
    ADD COLUMN IF NOT EXISTS featured boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS cover_variant varchar(64) NOT NULL DEFAULT 'IMAGE',
    ADD COLUMN IF NOT EXISTS experience_variant varchar(64);

-- Preserve the existing public/draft state before making the new status
-- authoritative. New rows default to DRAFT and only the publish workflow can
-- promote them to PUBLISHED.
UPDATE public."Projects"
SET publication_status = CASE
    WHEN published_at IS NULL THEN 'DRAFT'
    ELSE 'PUBLISHED'
END
WHERE publication_status IS NULL;

ALTER TABLE public."Projects"
    ALTER COLUMN publication_status SET DEFAULT 'DRAFT',
    ALTER COLUMN publication_status SET NOT NULL;

COMMENT ON COLUMN public."Projects".summary IS
    'Bounded project summary used by listings, search documents and notifications.';

COMMENT ON COLUMN public."Projects".publication_status IS
    'DRAFT, PUBLISHED or ARCHIVED; controls public visibility independently of source retention.';

COMMENT ON COLUMN public."Projects".featured IS
    'Database-owned homepage curation flag. Non-featured published projects remain in the archive.';

COMMENT ON COLUMN public."Projects".cover_variant IS
    'Presentation contract used by the frontend cover renderer; IMAGE uses image_url.';

COMMENT ON COLUMN public."Projects".experience_variant IS
    'Optional interactive project-detail experience selected by data rather than project ID.';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'projects_publication_status_check'
    ) THEN
        ALTER TABLE public."Projects"
            ADD CONSTRAINT projects_publication_status_check
            CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_projects_public_listing
    ON public."Projects" (featured DESC, num DESC)
    WHERE publication_status = 'PUBLISHED';
