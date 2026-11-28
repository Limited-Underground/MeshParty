import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import worker from "../src/index.mjs";
import { clearAccessKeys } from "../src/review.mjs";
import { createSqliteD1 } from "./sqlite-d1.mjs";

const team = "https://example-team.cloudflareaccess.com";
const aud = "0123456789abcdef0123456789abcdef";
const owner = "owner@example.com";

const encode = value => Buffer.from(typeof value === "string" ? value : JSON.stringify(value))
  .toString("base64url");

async function keys() {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true, ["sign", "verify"]);
  const jwk = { ...(await crypto.subtle.exportKey("jwk", pair.publicKey)), kid: "kid-1", alg: "RS256", use: "sig" };
  const sign = async (claims, kid = "kid-1") => {
    const head = encode({ alg: "RS256", kid, typ: "JWT" });
    const body = encode(claims);
    const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", pair.privateKey,
      new TextEncoder().encode(`${head}.${body}`));
    return `${head}.${body}.${Buffer.from(signature).toString("base64url")}`;
  };
  return { jwk, sign };
}

async function setup(t) {
  clearAccessKeys();
  const { db, binding } = createSqliteD1();
  t.after(() => db.close());
  const { jwk, sign } = await keys();
  const realFetch = globalThis.fetch;
  const fetched = [];
  globalThis.fetch = async url => {
    fetched.push(String(url));
    if (String(url) === `${team}/cdn-cgi/access/certs`) return Response.json({ keys: [jwk] });
    return new Response(null, { status: 200 });
  };
  t.after(() => { globalThis.fetch = realFetch; });
  const env = {
    REPORTS: binding,
    REPORT_LIMITER: { limit: async () => ({ success: true }) },
    RETENTION_DAYS: "30",
    ACCESS_TEAM_DOMAIN: team,
    ACCESS_AUD: aud,
    REVIEW_EMAILS: `Admin@Example.com, ${owner}`,
  };
  const now = Math.floor(Date.now() / 1000);
  const claims = (extra = {}) => ({ iss: team, aud: [aud], email: owner, exp: now + 600, iat: now, ...extra });
  const open = (token, init = {}) => worker.fetch(new Request("https://reports.example.com/review", {
    headers: token ? { "Cf-Access-Jwt-Assertion": token } : {}, ...init,
  }), env);
  const post = (path, body) => worker.fetch(new Request(`https://reports.example.com${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.10" },
    body: JSON.stringify(body),
  }), env);
  return { env, sign, claims, open, post, fetched };
}

test("the owner sees current feedback and reports, newest first, with HTML escaped", async t => {
  const { sign, claims, open, post } = await setup(t);
  assert.equal((await post("/v1/feedback", {
    feedbackId: randomUUID(), kind: "bug", message: "First <script>alert(1)</script>",
    details: { versionName: "0.2.0", versionCode: 10, androidVersion: "16", manufacturer: "samsung",
      model: "SM-S928U", meshtasticConnected: true, meshCoreConnected: false },
  })).status, 201);
  assert.equal((await post("/v1/reports", {
    reportId: randomUUID(), kind: "message", reason: "spam", network: "meshcore", message: "Buy now",
  })).status, 201);

  const response = await open(await sign(claims()));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.match(response.headers.get("content-security-policy"), /default-src 'none'/);
  assert.equal(response.headers.get("x-robots-tag"), "noindex, nofollow");
  const html = await response.text();
  assert.ok(html.includes("First &lt;script&gt;alert(1)&lt;/script&gt;"), "message text is escaped");
  assert.ok(!html.includes("<script>"), "no script reaches the page");
  assert.ok(html.includes("MeshParty 0.2.0 (10) · Android 16 · samsung SM-S928U"), "app and phone details");
  assert.ok(html.includes("spam") && html.includes("Buy now"), "abuse report shown");
  assert.ok(html.includes("Signed in as owner@example.com"));
});

test("the page is closed without a valid Access token for an allowed email", async t => {
  const { sign, claims, open, env } = await setup(t);
  assert.equal((await open(null)).status, 404, "no token");
  assert.equal((await open("not.a.token")).status, 404, "garbage token");
  assert.equal((await open(await sign(claims({ email: "someone@example.com" })))).status, 404, "other email");
  assert.equal((await open(await sign(claims({ aud: ["another-app-audience-tag"] })))).status, 404, "wrong audience");
  assert.equal((await open(await sign(claims({ iss: "https://evil.cloudflareaccess.com" })))).status, 404, "wrong issuer");
  assert.equal((await open(await sign(claims({ exp: Math.floor(Date.now() / 1000) - 5 })))).status, 404, "expired");
  assert.equal((await open(await sign(claims(), "unknown-kid"))).status, 404, "unknown key");
  const good = await sign(claims());
  const [head, body] = good.split(".");
  const forged = `${head}.${encode({ ...claims(), email: "Admin@Example.com" })}.${good.split(".")[2]}`;
  assert.equal((await open(forged)).status, 404, "changed claims break the signature");
  assert.equal((await open(`${head}.${body}.${good.split(".")[2]}`)).status, 200, "the untouched token still works");
  assert.equal((await open(good, { method: "POST" })).status, 405);

  const upper = await sign(claims({ email: "ADMIN@example.com" }));
  assert.equal((await open(upper)).status, 200, "email match ignores case");

  delete env.ACCESS_AUD;
  assert.equal((await open(good)).status, 404, "review stays off until configured");
});

test("alerts link to the review page when REVIEW_URL is set", async t => {
  const { env, post, fetched } = await setup(t);
  env.ALERT_URL = "https://ntfy.example.com/topic";
  env.REVIEW_URL = "https://reports.example.com/review";
  const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ url: String(url), init }); return new Response(null); };
  assert.equal((await post("/v1/feedback", { feedbackId: randomUUID(), kind: "like", message: "Nice" })).status, 201);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].init.headers.Click, "https://reports.example.com/review");
  assert.equal(fetched.length, 0);
});
