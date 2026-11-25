# Cloudflare R2 compatibility check for the media pipeline

*Researched 25 November 2026 against developers.cloudflare.com/r2 (pages: api/s3/api, api/s3/presigned-urls, buckets/cors, examples/aws/aws-sdk-java, pricing, platform/limits, buckets/object-lifecycles, buckets/data-location, buckets/event-notifications, api/tokens, buckets/public-buckets). Docs and code were read only; **nothing was run against a real R2 bucket.** For use in M1.5 (staging).*

**Verdict:** the M2a design works on R2 with a small code change and platform-side setup. One behaviour is unverified: whether R2 enforces a signed `Content-Length`.

| Feature | Works as is? | Change |
|---|---|---|
| Presigned PUT, `Content-Type` signed | Yes (mismatch → 403 `SignatureDoesNotMatch`) | none |
| Presigned PUT, `Content-Length` signed | **Unverified** (docs never mention it) | Smoke-test it. `complete` already compares the HEAD size with the declared size and deletes oversize objects, so the safeguard exists |
| Presign expiry | Yes (1 s to 7 days; we use 10 min / 5 min) | none |
| Presigned URL host | Only on `<ACCOUNT_ID>.r2.cloudflarestorage.com`, not custom domains | Set `SCHOOL_STORAGE_PUBLIC_ENDPOINT` = `SCHOOL_STORAGE_ENDPOINT` |
| Presigned GET with `response-content-disposition` | Likely (unverified) | Include in the smoke test |
| HeadObject, ranged GetObject (4 KB sniff), DeleteObject, ListObjectsV2 | Yes | none |
| Server-side `PutObject` of variants | Probably: R2's Java page says the SDK's default chunked encoding gives a 403 signature mismatch | **Add `chunkedEncodingEnabled(false)`** to the `S3Configuration` in `S3ObjectStorage` (harmless on SeaweedFS); confirm in the smoke test |
| SDK checksum `WHEN_REQUIRED` | Yes, keep | none |
| `CreateBucket` / `PutBucketCors` / `HeadBucket` | Supported but need an Admin token | Keep `StorageBootstrap` off outside local; do bucket, CORS and lifecycle in IaC |
| ACLs, tagging, versioning, object lock, bucket policy, SSE-KMS | Not supported | We use none; don't add any |
| Region `auto`, path-style | Yes | none |

## Changes, in priority order
1. Run the smoke test below, Content-Length first.
2. `S3ObjectStorage`: `chunkedEncodingEnabled(false)`.
3. **CORS is platform work** (IaC, dashboard or wrangler): exact web origins, methods PUT/GET/HEAD, allowed header `Content-Type` (plus any `x-amz-*` the BFF sends), expose `ETag`, max-age up to 86400 (browsers may cap at 2 h); changes take up to ~30 s. Mobile ignores CORS.
4. **Credentials:** runtime token = Object Read & Write scoped to the one bucket; a separate Admin token only in IaC (never in the app). Prefer account-owned tokens.
5. **Lifecycle:** a prefix rule expiring stale `PENDING_UPLOAD` objects (~1 day). Deletion happens within ~24 h of expiry, so DB cleanup (`MediaMaintenance`) stays. Never expire READY objects by lifecycle.
6. **Event notifications:** no change (Cloudflare Queue only, delivery guarantees undocumented). Keep `complete` + HeadObject.
7. Presigned GETs go through the S3 endpoint and are **not edge-cached**; they are bearer tokens until expiry. Keep the 5-minute TTL for children's photos.

## Serving private media
- **Now:** presigned GET, as built.
- **Later:** a Worker or custom-domain layer in front of a private bucket (token check in the Worker, stream from the R2 binding, edge caching). Gives stable cacheable URLs and hides the bucket host, but adds a second auth implementation; defer until traffic justifies it (a WAF HMAC route needs Pro or higher).
- **Ethiopian users:** a Cloudflare point of presence in Addis Ababa is **unverified** (third-party listing only). Assume the nearest are Nairobi, Mombasa, Djibouti or Cairo until confirmed by a `cf-ray` header from an Ethiopian network. Server-side image variants already help. R2 egress is free.

## Data location (not legal advice)
- Location hints are best effort, set only at bucket creation; the regions are wnam, enam, weur, eeur, apac, oc. **No African region.**
- Jurisdictions are guaranteed: `eu`, `fedramp` (Enterprise), `us`. **No Ethiopian or African jurisdiction.** A jurisdiction bucket uses `https://<ACCOUNT_ID>.<jurisdiction>.r2.cloudflarestorage.com` and cannot be changed later.
- Decide EU jurisdiction vs default placement **before creating the production bucket**, with counsel (Proclamation 1321/2024).

## Cost and limits
Free tier: 10 GB-month, 1M Class A and 10M Class B requests/month. Then $0.015/GB-month, Class A $4.50/M, Class B $0.36/M, **egress free**. Object max 5 TiB, single PUT 5 GiB, metadata 8 KiB, key 1,024 bytes, 1 write per second per key. Buckets are private by default; `r2.dev` is for testing only.

## Environment (staging/prod, no defaults)
`SCHOOL_STORAGE_ENDPOINT=https://<ACCOUNT_ID>[.eu].r2.cloudflarestorage.com`, `SCHOOL_STORAGE_PUBLIC_ENDPOINT` (same value), `SCHOOL_STORAGE_REGION=auto`, `SCHOOL_STORAGE_BUCKET` (one per environment), `SCHOOL_STORAGE_ACCESS_KEY` / `SCHOOL_STORAGE_SECRET_KEY` (Object RW token), `SCHOOL_STORAGE_CREATE_BUCKET` unset/false; web `NEXT_PUBLIC_UPLOAD_MODE=direct` once CORS is in place (`proxy` as fallback).

## Manual smoke test against a real staging bucket
There is no S3-API emulator for R2, so keep the SeaweedFS container tests in CI and add this manual run:
1. Presign a PUT (type + length signed), upload an exact-size file: expect 200.
2. Upload with a different `Content-Type`: expect 403.
3. Upload with a larger and a smaller size: **record whether R2 rejects it** (the open item).
4. HeadObject; ranged GetObject `bytes=0-4095` (expect 206); server-side `putObject` of a small variant (chunked-encoding workaround).
5. Presign a GET with `response-content-disposition`; confirm the header returns.
6. Browser PUT from the staging web origin: CORS preflight and `ETag`.
7. Delete the object, then delete again: no error.
8. Create a lifecycle rule on a test prefix; read it back.
9. Confirm the runtime token is rejected for another bucket.

## Questions for the owner
1. EU jurisdiction, or default placement with no residency guarantee?
2. Worker/custom-domain serving layer for private media: plan for it, or defer?
3. Who owns the IaC for bucket, CORS and lifecycle?
4. Retention for deleted media and stale `PENDING_UPLOAD` rows (sets the lifecycle days)?
