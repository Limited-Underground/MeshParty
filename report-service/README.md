# MeshParty abuse report service

MeshParty sends the report a user reviews and confirms to `POST /v1/reports` over HTTPS. A successful response is `{"receipt":"<report UUID>"}`. A repeated request with the same UUID and details returns the same receipt and keeps the original deletion deadline. A repeated UUID with changed details returns a conflict.

The service stores the report type, reason, mesh network, and selected evidence. Evidence can include the selected message, a mesh sender ID, or a reported MeshCore channel display name. Cloudflare receives connection metadata. The Worker hashes the edge-supplied IP address for temporary rate limiting. Reports in D1 contain neither the address nor its hash.

## Local verification

Run this from the MeshParty workspace with Node.js 24 or later. The tests execute the Worker's SQL against local SQLite using the checked-in migration. They require no Cloudflare account and use fictional reports.

```powershell
node --test report-service/test/report-service.test.mjs
```

## Set up a reporting service

Use the example configuration to set up an instance in your own Cloudflare account. The MeshParty Play build uses the Limited Underground reporting service.

1. Confirm the custom HTTPS hostname, the people who will review reports, and the retention period. This service uses a dedicated database. Wrangler 4.36 or later is required for the rate limiter. [Cloudflare rate limiter](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)
2. From this directory, copy `wrangler.example.jsonc` to `wrangler.jsonc`. Set the approved hostname, the dedicated D1 database name and UUID, and a rate limit namespace ID unused in the Cloudflare account. Leave the public Workers preview URLs and Worker observability disabled.
3. Apply the migration to that database, deploy the Worker, and test a fictional submission against the exact HTTPS URL. Confirm the receipt matches the submitted UUID and the record appears in the authenticated database console. Test that a browser visit to the report endpoint returns no records and that the scheduled purge works. [Cloudflare D1 migrations](https://developers.cloudflare.com/d1/reference/migrations/) · [Cloudflare Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/)

```powershell
wrangler d1 migrations apply meshparty-abuse-reports --remote --config wrangler.jsonc
wrangler deploy --config wrangler.jsonc
```

Use the actual database name in the migration command if it differs from the example. Configure the Android build with `-PmeshPartyReportUrl=https://<approved-host>/v1/reports` after the endpoint is live. Update the public and in-app privacy policies to reflect the approved submission and retention behavior before release.

## Review and retention

Use the authenticated Cloudflare D1 console with access restricted to designated reviewers. The Worker exposes only report intake. For the review queue, this query excludes expired rows even if the daily purge is still pending.

```sql
SELECT report_id, datetime(received_at, 'unixepoch') AS received_utc,
       kind, reason, network, message, sender_id, sender_name
FROM abuse_reports
WHERE expires_at > unixepoch()
ORDER BY received_at DESC
LIMIT 100;
```

The live configuration expires reports after 30 days. `RETENTION_DAYS` can be set to 1–90 days for new reports. The daily job at 03:13 UTC purges expired rows from the active database. Cloudflare D1's Time Travel keeps backups according to the plan, currently up to 7 days on Workers Free or 30 days on Workers Paid. Account for those recoverable copies in deletion disclosures. [Cloudflare D1 backup retention](https://developers.cloudflare.com/d1/reference/time-travel/)
