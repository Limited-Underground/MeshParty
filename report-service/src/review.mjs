// Private review page for the owner (D-209): current feedback and abuse reports, newest first.
// Cloudflare Access protects /review in front of the Worker, and the Worker verifies the Access
// token itself, so the page stays closed even if a route ever bypasses Access.

const certCache = new Map();
const CERT_TTL_MS = 60 * 60 * 1000;
const pageHeaders = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "X-Robots-Tag": "noindex, nofollow",
  "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
};

const feedbackQuery =
  "SELECT received_at, kind, message, details_json FROM feedback WHERE expires_at > unixepoch() " +
  "ORDER BY received_at DESC, feedback_id LIMIT 200";
const reportQuery =
  "SELECT received_at, kind, reason, network, message, sender_id, sender_name FROM abuse_reports " +
  "WHERE expires_at > unixepoch() ORDER BY received_at DESC, report_id LIMIT 200";

/** Returns the review page, or 404 when review is not configured or the caller is not allowed. */
export async function handleReview(request, env, now = Date.now()) {
  if (request.method !== "GET") return text(405, { Allow: "GET" });
  const email = await accessEmail(request, env, now);
  if (!email) return text(404);
  const [feedback, reports] = await env.REPORTS.batch([
    env.REPORTS.prepare(feedbackQuery),
    env.REPORTS.prepare(reportQuery),
  ]);
  return new Response(page(feedback.results ?? [], reports.results ?? [], email), { status: 200, headers: pageHeaders });
}

function text(status, headers = {}) {
  return new Response(null, { status, headers: { "Cache-Control": "no-store", ...headers } });
}

/**
 * Verifies the Cf-Access-Jwt-Assertion header against the Access team's signing keys, the
 * application's audience tag and the allowed email list. Returns the email, or null.
 */
export async function accessEmail(request, env, now = Date.now()) {
  const team = env.ACCESS_TEAM_DOMAIN;
  const audience = env.ACCESS_AUD;
  const allowed = String(env.REVIEW_EMAILS ?? "").toLowerCase().split(",").map(s => s.trim()).filter(Boolean);
  if (typeof team !== "string" || !/^https:\/\/[a-z0-9-]+\.cloudflareaccess\.com$/.test(team) ||
      typeof audience !== "string" || audience.length < 16 || allowed.length === 0) {
    return null;
  }
  const token = request.headers.get("Cf-Access-Jwt-Assertion");
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  let header, claims;
  try {
    header = JSON.parse(decode(parts[0]));
    claims = JSON.parse(decode(parts[1]));
  } catch {
    return null;
  }
  if (header.alg !== "RS256" || typeof header.kid !== "string") return null;
  const key = await signingKey(team, header.kid, now);
  if (!key) return null;
  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, bytes(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  if (!valid) return null;
  const seconds = Math.floor(now / 1000);
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (claims.iss !== team || !audiences.includes(audience) ||
      typeof claims.exp !== "number" || claims.exp <= seconds ||
      (typeof claims.nbf === "number" && claims.nbf > seconds + 60)) {
    return null;
  }
  const email = typeof claims.email === "string" ? claims.email.toLowerCase() : "";
  return allowed.includes(email) ? email : null;
}

async function signingKey(team, kid, now) {
  let cached = certCache.get(team);
  if (!cached || cached.at + CERT_TTL_MS < now || !cached.keys.has(kid)) {
    try {
      const response = await fetch(`${team}/cdn-cgi/access/certs`);
      if (!response.ok) return null;
      const body = await response.json();
      const keys = new Map();
      for (const jwk of body.keys ?? []) {
        if (jwk.kty !== "RSA" || typeof jwk.kid !== "string") continue;
        keys.set(jwk.kid, await crypto.subtle.importKey("jwk", jwk,
          { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]));
      }
      cached = { at: now, keys };
      certCache.set(team, cached);
    } catch {
      return null;
    }
  }
  return cached.keys.get(kid) ?? null;
}

export function clearAccessKeys() {
  certCache.clear();
}

function decode(part) {
  return new TextDecoder().decode(bytes(part));
}

function bytes(part) {
  const base64 = part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "=");
  return Uint8Array.from(atob(base64), c => c.charCodeAt(0));
}

const escapeMap = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, c => escapeMap[c]);
}

function when(seconds) {
  return new Date(Number(seconds) * 1000).toISOString().replace("T", " ").slice(0, 16) + " UTC";
}

function details(json) {
  if (!json) return "";
  let value;
  try { value = JSON.parse(json); } catch { return ""; }
  const parts = [
    value.versionName && `MeshParty ${value.versionName} (${value.versionCode})`,
    value.androidVersion && `Android ${value.androidVersion}`,
    [value.manufacturer, value.model].filter(Boolean).join(" "),
    `Meshtastic ${value.meshtasticConnected ? "connected" : "not connected"}`,
    `MeshCore ${value.meshCoreConnected ? "connected" : "not connected"}`,
  ].filter(Boolean);
  return `<p class="meta">${parts.map(esc).join(" · ")}</p>`;
}

function page(feedback, reports, email) {
  const feedbackCards = feedback.map(row => `
    <article><header><span class="tag">${esc(row.kind)}</span><time>${esc(when(row.received_at))}</time></header>
      <p class="msg">${esc(row.message)}</p>${details(row.details_json)}</article>`).join("") ||
    '<p class="empty">No feedback right now.</p>';
  const reportCards = reports.map(row => `
    <article class="report"><header><span class="tag">${esc(row.kind)} · ${esc(row.reason)}</span>
      <time>${esc(when(row.received_at))}</time></header>
      <p class="meta">${esc(row.network)}${row.sender_name ? " · name " + esc(row.sender_name) : ""}${row.sender_id ? " · " + esc(row.sender_id) : ""}</p>
      ${row.message ? `<p class="msg">${esc(row.message)}</p>` : ""}</article>`).join("") ||
    '<p class="empty">No abuse reports right now.</p>';
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#0f1517"><meta name="mobile-web-app-capable" content="yes">
<title>MeshParty review</title>
<style>
  body { margin:0; font:16px/1.4 system-ui, sans-serif; background:#0f1517; color:#edf3f0; }
  main { max-width:720px; margin:0 auto; padding:16px; }
  h1 { font-size:22px; margin:4px 0 2px; } h2 { font-size:18px; margin:22px 0 8px; color:#5fe3bb; }
  .who { color:#b3c3be; font-size:13px; margin:0 0 8px; }
  article { background:#1b2326; border:1px solid #2c3a3d; border-radius:12px; padding:12px 14px; margin:0 0 10px; }
  article.report { border-color:#7a5a2a; }
  header { display:flex; justify-content:space-between; gap:8px; flex-wrap:wrap; }
  .tag { font-weight:700; text-transform:capitalize; color:#5fe3bb; } .report .tag { color:#ffd396; }
  time { color:#b3c3be; font-size:13px; }
  .msg { white-space:pre-wrap; overflow-wrap:anywhere; margin:8px 0 0; }
  .meta { color:#b3c3be; font-size:13px; margin:6px 0 0; overflow-wrap:anywhere; }
  .empty { color:#b3c3be; }
</style></head>
<body><main>
<h1>MeshParty review</h1>
<p class="who">Signed in as ${esc(email)} · ${feedback.length} feedback · ${reports.length} reports · pull down to refresh</p>
<h2>Feedback</h2>${feedbackCards}
<h2>Abuse reports</h2>${reportCards}
</main></body></html>`;
}
