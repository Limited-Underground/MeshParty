# MeshParty report and feedback service

MeshParty sends the report a user reviews and confirms to `POST /v1/reports` over HTTPS. A successful response is `{"receipt":"<report UUID>"}`. A repeated request with the same UUID and details returns the same receipt and keeps the original deletion deadline. A repeated UUID with changed details returns a conflict.

The service stores the report type, reason, mesh network, and selected evidence. Evidence can include the selected message, a mesh sender ID, or a reported MeshCore channel display name. Cloudflare receives connection metadata. The Worker hashes the edge-supplied IP address for temporary rate limiting. Reports in D1 contain neither the address nor its hash.

## Feedback intake

`POST /v1/feedback` accepts HTTPS requests with `Content-Type: application/json`. Send a random UUIDv4 as `feedbackId`, a `kind` of `like`, `dislike`, `change`, `idea`, or `bug`, and a nonblank `message` of up to 2,000 UTF-16 code units. The request body limit is 16 KiB.

Omit `details` unless the user opts in. When present, include `versionName`, `versionCode`, `androidVersion`, `manufacturer`, `model`, `meshtasticConnected`, and `meshCoreConnected`. The four text fields allow up to 128 UTF-16 code units each. `versionCode` is an integer from 1 to 2,147,483,647, and both connection fields are booleans. Explicit `details: null` is equivalent to omission.

The Worker returns `201` and `{"receipt":"<feedback UUID>"}` after D1 confirms storage. While the row is retained, retrying the same UUID and payload returns the same receipt with `200`; changed data for that UUID returns `409`. The original `received_at` and `expires_at` are preserved. The existing Cloudflare limiter uses independent feedback and abuse report buckets, with the IP hash used only for rate limiting. Feedback rows contain the submission and its timestamps.

## Local verification

Run this from the MeshParty workspace with Node.js 24 or later. The tests execute the Worker's SQL against local SQLite using the checked-in migrations. They use fictional reports and feedback and require no Cloudflare account.

```powershell
node --test report-service/test/*.test.mjs
```

## Set up a reporting service

Use the example configuration to set up an instance in your own Cloudflare account. The MeshParty Play build uses the Limited Underground reporting service.

1. Confirm the custom HTTPS hostname, the people who will review reports, and the retention period. This service uses a dedicated database. Wrangler 4.36 or later is required for the rate limiter. [Cloudflare rate limiter](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)
2. From this directory, copy `wrangler.example.jsonc` to `wrangler.jsonc`. Set the approved hostname, the dedicated D1 database name and UUID, and a rate limit namespace ID unused in the Cloudflare account. Leave the public Workers preview URLs and Worker observability disabled.
3. Apply the migrations to that database and deploy the Worker. For an existing installation, apply `0002_create_feedback.sql` before deploying the Worker that calls it. The existing D1, limiter, custom domain and cron bindings support both endpoints. Test one fictional submission per endpoint and match each receipt UUID to its row in the authenticated database console. Verify that a browser GET receives no records and that the scheduled purge works. [Cloudflare D1 migrations](https://developers.cloudflare.com/d1/reference/migrations/) · [Cloudflare Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/)

```powershell
wrangler d1 migrations apply meshparty-abuse-reports --remote --config wrangler.jsonc
wrangler deploy --config wrangler.jsonc
```

Use the actual database name in the migration command if it differs from the example. The Worker serves the report endpoint at `https://<approved-host>/v1/reports` and feedback at `https://<approved-host>/v1/feedback`. Configure the app and publish its matching privacy policies after verifying both endpoints.

## Alerts for new submissions

The service can push a short notice to the owner's phone through [ntfy](https://ntfy.sh) when a new feedback item or abuse report is stored (D-208). The notice names only the type and its fixed category, for example "New feedback: idea" or "New sender report: harassment, meshtastic". It never includes the message text, sender names, sender IDs, receipt IDs or app details, and a retry of an existing submission does not alert again. A failed alert never changes the app's receipt.

1. Install the ntfy app on the phone and subscribe to a long random topic name (it works like a password; anyone who knows it can read the alerts).
2. Store the topic URL as a Worker secret, then deploy:

   ```sh
   wrangler secret put ALERT_URL --config wrangler.jsonc   # value: https://ntfy.sh/<your-topic>
   wrangler deploy --config wrangler.jsonc
   ```

3. Send one fictional feedback item from a test build and check that the phone shows the alert.

Without `ALERT_URL`, or with a URL that is not `https://`, the service sends no alerts.

## Private review page

`https://<report host>/review` shows the current feedback and abuse reports, newest first, for reading on a phone (D-209). Add it to the phone's home screen to open it like an app. Each alert opens it when `REVIEW_URL` is set.

The page is protected twice. A Cloudflare Access self-hosted application on `<report host>/review` asks for a one-time email code first. The Worker then verifies the Access token (`Cf-Access-Jwt-Assertion`) itself: RS256 signature against the team's keys, issuer, application audience tag, expiry and an allowed email. Without all four settings below, `/review` answers 404.

| Setting (Worker variable) | Value |
|---|---|
| `ACCESS_TEAM_DOMAIN` | `https://<team>.cloudflareaccess.com` |
| `ACCESS_AUD` | The Access application's audience (AUD) tag |
| `REVIEW_EMAILS` | Comma-separated emails allowed to read, matching the Access policy |
| `REVIEW_URL` | `https://<report host>/review`, for the alert link |

The page is read-only, uncached and not indexed, and it allows no scripts.

## Review and retention

Use the authenticated Cloudflare D1 console with access restricted to designated reviewers. The Worker exposes write-only intake endpoints. For the abuse report queue, this query excludes expired rows even if the daily purge is still pending.

```sql
SELECT report_id, datetime(received_at, 'unixepoch') AS received_utc,
       kind, reason, network, message, sender_id, sender_name
FROM abuse_reports
WHERE expires_at > unixepoch()
ORDER BY received_at DESC
LIMIT 100;
```

The live configuration expires reports after 30 days. `RETENTION_DAYS` can be set to 1–90 days for new reports. The daily job at 03:13 UTC purges expired rows from the active database. Cloudflare D1's Time Travel keeps backups according to the plan, currently up to 7 days on Workers Free or 30 days on Workers Paid. Account for those recoverable copies in deletion disclosures. [Cloudflare D1 backup retention](https://developers.cloudflare.com/d1/reference/time-travel/)

### Read or export feedback

The owner can run this query in the authenticated D1 console for the existing database. Each feedback row expires 90 days from receipt. The daily job purges expired feedback and the query filters it out as soon as it expires.

```sql
SELECT feedback_id, datetime(received_at, 'unixepoch') AS received_utc,
       datetime(expires_at, 'unixepoch') AS expires_utc, kind, message, details_json
FROM feedback
WHERE expires_at > unixepoch()
ORDER BY received_at DESC, feedback_id;
```

For JSON, the owner can run the same query with Wrangler authenticated to the Cloudflare account and with access to the existing D1 database. From this directory, use the actual database name and an approved private export path. Export copies need the same access restrictions and must be removed by their original expiry. Cloudflare Time Travel retention also applies to feedback. [Wrangler D1 commands](https://developers.cloudflare.com/d1/wrangler-commands/#d1-execute)

```powershell
$feedbackQuery = 'SELECT feedback_id, received_at, expires_at, kind, message, details_json FROM feedback WHERE expires_at > unixepoch() ORDER BY received_at DESC, feedback_id;'
wrangler d1 execute meshparty-abuse-reports --remote --config wrangler.jsonc --command $feedbackQuery --json > 'D:\path\to\private\meshparty-feedback.json'
```
