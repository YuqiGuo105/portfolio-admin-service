# Original deletion: GCS signature compatibility

## Failure

The generation-pinned DELETE carried `x-goog-if-generation-match` but used AWS V4
authentication (`x-amz-date` and `x-amz-content-sha256`). GCS rejects this mixed
header family with HTTP 400 `ExcessHeaderValues`. The admin API then returned the
generic deletion-not-confirmed error, so refreshing the check could not fix it.

## Fix and safety boundaries

Requests containing Google extension headers now use native `GOOG4-HMAC-SHA256`,
the `storage/goog4_request` credential scope and Google authentication headers.
Existing AWS signed requests and presigned multipart uploads retain their format.
Explicitly mixed input header families fail before HTTP.

Generation matching, upload ownership, persisted deletion intent, metadata checks,
ADMIN authorization and post-delete HEAD confirmation are unchanged. Existing
DELETE_FAILED rows remain available for an administrator's manual retry; deployment
does not retry or delete anything automatically. Public renditions are not touched.

Rejected DELETE responses now log only the asset ID, HTTP status and bounded error
code, never the response body, private object path or credentials.

Reference: [Google Cloud Storage signatures](https://docs.cloud.google.com/storage/docs/authentication/signatures).

## Verification

- Reproduced the provider's mixed-header rejection on a conditional request for a
  newly generated disposable object, without touching existing media.
- The same production signer after the fix completed PUT 200, HEAD 200,
  generation-conditional DELETE 204 and HEAD 404 against the private GCS bucket.
- Local HTTP E2E generates and uploads a fresh video, runs actual FFmpeg
  transcoding, deletes the original through the admin controller, and decodes HLS
  and MP4 afterward. It verifies unchanged public manifests and presentation links.
- Regression tests cover generation replacement, retry after failure, concurrent
  duplicates, permissions, stale metadata, AWS signature compatibility and safe
  failure diagnostics. The loopback fake now rejects the same mixed headers as GCS.

Run against an isolated loopback test database, with FFmpeg/libvips on PATH:

```sh
MEDIA_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55449/media_delete_test \
mvn -Pmedia-e2e -pl admin-service \
  -Dtest=MediaOriginalDeletionEndToEndTest,MediaOriginalDeletionTest,OriginalsStoreDeletionTest,S3SignerTest test
```
