-- Older rows were written before the application tracked Kafka acknowledgements.
-- Their PENDING state is therefore ambiguous: many were already delivered by the
-- old fire-and-forget publisher. Quarantine them at the relay cutover boundary so
-- deployment cannot resend historical subscriber emails.
UPDATE public.content_event_outbox
   SET status = 'DLQ',
       last_error = 'Quarantined at reliable-outbox cutover; delivery state is unknown',
       updated_at = CURRENT_TIMESTAMP
 WHERE status IN ('PENDING', 'PROCESSING')
   AND created_at < CURRENT_TIMESTAMP;
