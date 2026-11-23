# Media pipeline local verification

Date: 2026-11-22. Local changes only; no production migration, credentials, cloud resources,
upload, commit or deployment performed. Uploads below use generated fixtures and loopback services.

## Scope

Reviewed the existing `feature/media-pipeline` implementation in this worktree and the sibling
`Portfolio-media` frontend. Workflow diagrams, cover artwork and URL changes remain separate.

Fixed upload expiry enforcement, presign expiry bounds, part-number/size validation, and orphan
multipart cleanup on a failed database insert. Resume now checks client file metadata and the
server session before sending parts; metadata comparison prevents accidental wrong-file resume,
not deliberate content substitution. Failed parallel uploads cancel remaining requests.

The browser player now starts explicitly, lazily imports HLS, exposes quality selection, and
cleans up on navigation. Audio videos no longer fall back to the silent loop MP4. Article media
lookups have a 1.5-second abort and a 50-reference bound, falling back to stored article previews.
The local sample server handles byte ranges and bounds real paths, including symlinks.

FFmpeg and ffprobe input commands restrict both protocols and demuxers. See the official
[format whitelist documentation](https://ffmpeg.org/ffmpeg-formats.html#Format-Options).
This is defense in depth, not a replacement for running the media worker in an isolated container.

## Verified

- All four Java modules passed Maven `verify` with a disposable PostgreSQL on loopback, including
  9 upload flow tests, 4 validation tests, 7 durable wake tests and 12 database failure/recovery tests.
- Installed FFmpeg 9.0.2 (including ffprobe) and libvips 8.18.7 (including vipsheader) in
  `../.media-tools/runtime`, without changing system packages or shell startup files.
- All 33 media-service tests ran with real tools, including the three formerly skipped transcode
  tests. Fresh video generated aligned HLS renditions, poster and loop MP4; fresh PNG generated
  AVIF/WebP/JPEG outputs; invalid video failed once with a sanitized reason.
- The opt-in `media-e2e` test starts actual upload/recovery HTTP controllers, the real admin auth
  filter, a Kafka KRaft broker, worker HTTP controller and job runner. It uses disposable PostgreSQL
  schemas and Moto's loopback S3 API with explicitly fake credentials, never ambient AWS credentials.
  Node runs the SAME frontend upload helper against those HTTP APIs. A fresh 1080p video is sent in
  four multipart pieces, followed by a fresh PNG. Both become READY with 27 public output objects.
- Fault injection: the first part PUT returns 503 and is retried; the first worker wake returns
  503, remains durably pending, and succeeds on the second attempt through the external local
  scheduler process. Duplicate completion and an actual Kafka replay leave two PLAN jobs and two
  published assets, not duplicates. Missing admin/recovery credentials return 401.
- Wake HTTP contract tests reject 202, 401, malformed JSON, a different asset ID and PROCESSING;
  only a matching READY/FAILED response completes delivery. Lease tests reject stale owners,
  recover after restart and park exhausted retries even when the last process died before finish.
- After adding input demuxer restrictions, all 11 `MediaMathTest` cases passed.
- Frontend: 20 media tests and 598 existing regression tests passed; focused lint passed.
- Next.js production build passed, with existing lint and unavailable-font-optimization warnings.
- Chrome: the NEWLY uploaded/transcoded video played at 1080p on desktop and to the end at 720p
  in a 390px viewport, with no media errors or horizontal overflow. The untouched second video
  remained unloaded. Fresh AVIF loaded at 1500px on desktop and 640px in the mobile viewport.
  FFmpeg independently decoded all audio/video streams in the generated master playlist.
  Native HLS has a unit test; real iOS Safari is not verified.
- The test database is disposable and contains no production records. The sample playback server
  is loopback-only and does not accept uploads.

## Wake behavior

V12 adds a separate wake delivery state to the existing outbox. Kafka delivery marks `status=SENT`;
the wake dispatcher later claims due rows with `FOR UPDATE SKIP LOCKED`, a lease and a fencing token.
Two independent HTTP workers hold requests open while processing. Failures/202 responses retry after
5, 10, 20 ... up to 300 seconds. The default 20-attempt limit includes expired leases after crashes.
Exhausted rows are retained as `wake_status=DLQ` and reported by the recovery endpoint; they are not
silently deleted or endlessly retried. Inspect the asset and failure before any operator retry.

`POST /api/internal/media/recover` requires its own 32+ character token via
`X-Media-Recovery-Token`, reads `MEDIA_RECOVERY_TOKEN`, and returns no-store aggregate counts.
It invokes the relay and WAITS for the active wake batch, including a batch begun by an in-process
timer. Keeping this request alive matters on request-billed hosts. A separate process runs
`scripts/media-recovery-loop.mjs` in the local E2E; production must use an external scheduler.
The relay starts at most 20 deliveries per pass and starts no new send after five seconds. A send
already in progress has its own transport timeout. Unclaimed work stays due, not under an idle lease.

The default wake request waits up to 600 seconds plus 15 seconds of network allowance. Configure
the admin request timeout and scheduler deadline with additional broker-delivery allowance, e.g.
900 seconds. The token must stay
server-side; use Secret Manager and scheduler IAM/OIDC in a later deployment, not a browser timer.

## Production gates

- Fifteen unrelated knowledge/RAG database tests remain skipped in the full reactor run.
- The object store is a local S3 emulator, not the future cloud provider. Cloud IAM, CORS and
  provider-specific multipart behavior still need staging verification.
- No Cloud Scheduler, production secrets, database migration or Cloud Run configuration was
  created/applied. The independent local scheduler proves the request/retry flow, not actual
  Cloud Run scale-to-zero/CPU allocation. Verify that behavior in staging before enabling uploads.
- READY outputs are public before their article is published. Use only public media unless a
  private/public publication policy and access model are added.
- Object-store permissions, CORS, lifecycle cleanup, resource limits, disk use and a spending
  alert/limit need staging verification. No claim is made that the proposed service is free.

## Reproduce

Backend, with a disposable test database and JDK 21:

```sh
export PATH="$PWD/../.media-tools/runtime/bin:$PATH"
MEDIA_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/media_pipeline_test mvn verify
```

This machine only has JDK 25; the run additionally used
`-Dnet.bytebuddy.experimental=true` for the repository's older Mockito/Byte Buddy version.
No dependency upgrade was included.

Full local HTTP/Kafka test, from this backend root, with PostgreSQL already running:

```sh
# Dedicated terminal; loopback only. Moto 5.2.5 is installed in the isolated runtime.
../.media-tools/runtime/bin/moto_server -H 127.0.0.1 -p 19090
```

```sh
../.media-tools/runtime/bin/python scripts/media-local-storage.py
MEDIA_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/media_pipeline_test \
MEDIA_E2E_FRONTEND="$PWD/../Portfolio-media" \
MEDIA_E2E_OUTPUT=/tmp/portfolio-media-http-e2e \
mvn -Pmedia-e2e -pl admin-service -Dtest=MediaHttpEndToEndTest test
../.media-tools/runtime/bin/python scripts/media-local-storage.py --export /tmp/portfolio-media-http-e2e
```

The test-only Maven profile imports media-service sources into the admin TEST classpath, not its
production jar. Kafka, HTTP services and the scheduler stop at test completion; each database
schema is dropped. The external Moto server can then be stopped. `verification.json`, `upload.log`,
`recovery.log` and fresh published files stay in the chosen output directory. No real credentials
are written there. Outputs use generated audiovisual test patterns, not portfolio article images.

Frontend, in `Portfolio-media`:

```sh
npm run test:media
npm test
npm run build
PORT=3154 MEDIA_SAMPLE_DIR=/tmp/portfolio-media-http-e2e npm run dev:media
```

Preview: `http://127.0.0.1:3154/media-preview`. It plays the fresh end-to-end outputs and does not
accept uploads itself. The real upload/API/Kafka flow is exercised by the opt-in test above.
Production article covers, Read next artwork, workflow diagrams and URL behavior were not changed.
