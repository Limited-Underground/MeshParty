import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import worker from "../src/index.mjs";
import { createSqliteD1 } from "./sqlite-d1.mjs";

const alertUrl = "https://ntfy.example.com/meshparty-owner-topic";

function setup(t, alert = alertUrl) {
  const { db, binding } = createSqliteD1();
  t.after(() => db.close());
  const env = {
    REPORTS: binding,
    REPORT_LIMITER: { limit: async () => ({ success: true }) },
    RETENTION_DAYS: "30",
    ALERT_URL: alert,
  };
  const calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init });
    return new Response(null, { status: 200 });
  };
  t.after(() => { globalThis.fetch = realFetch; });
  const waits = [];
  const ctx = { waitUntil: promise => waits.push(promise) };
  const post = (path, body) => worker.fetch(new Request(`https://reports.example.com${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.9" },
    body: JSON.stringify(body),
  }), env, ctx);
  return { env, calls, waits, post };
}

const secret = "Private words 🌲 from Ana";

test("new feedback sends one alert with the kind only, retries send none", async t => {
  const { calls, waits, post } = setup(t);
  const draft = { feedbackId: randomUUID(), kind: "idea", message: secret };
  assert.equal((await post("/v1/feedback", draft)).status, 201);
  await Promise.all(waits);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, alertUrl);
  assert.equal(calls[0].init.method, "POST");
  assert.equal(calls[0].init.headers.Title, "MeshParty feedback");
  assert.equal(calls[0].init.body, "New feedback: idea. Read it in the D1 console.");
  assert.ok(!JSON.stringify(calls[0]).includes("Private"), "the message text never leaves");
  assert.ok(!JSON.stringify(calls[0]).includes(draft.feedbackId), "the receipt ID never leaves");

  assert.equal((await post("/v1/feedback", draft)).status, 200);
  await Promise.all(waits);
  assert.equal(calls.length, 1, "a retry of the same feedback is not a new alert");
});

test("new abuse report sends one alert with its fixed categories only", async t => {
  const { calls, waits, post } = setup(t);
  const report = {
    reportId: randomUUID(), kind: "sender", reason: "harassment", network: "meshtastic",
    message: secret, senderId: "node:0000abcd",
  };
  assert.equal((await post("/v1/reports", report)).status, 201);
  await Promise.all(waits);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].init.headers.Title, "MeshParty abuse report");
  assert.equal(calls[0].init.body, "New sender report: harassment, meshtastic. Review it in the D1 console.");
  const sent = JSON.stringify(calls[0]);
  for (const hidden of ["Private", "0000abcd", report.reportId]) assert.ok(!sent.includes(hidden), hidden);
});

test("no alert without an https ALERT_URL, for invalid input, or when ntfy fails", async t => {
  const plain = setup(t, null);
  assert.equal((await plain.post("/v1/feedback", { feedbackId: randomUUID(), kind: "bug", message: "x" })).status, 201);
  await Promise.all(plain.waits);
  assert.equal(plain.calls.length, 0);

  const insecure = setup(t, "http://ntfy.example.com/topic");
  assert.equal((await insecure.post("/v1/feedback", { feedbackId: randomUUID(), kind: "bug", message: "x" })).status, 201);
  await Promise.all(insecure.waits);
  assert.equal(insecure.calls.length, 0);

  const bad = setup(t);
  assert.equal((await bad.post("/v1/feedback", { feedbackId: "nope", kind: "bug", message: "x" })).status, 400);
  await Promise.all(bad.waits);
  assert.equal(bad.calls.length, 0);

  const failing = setup(t);
  globalThis.fetch = async () => { throw new Error("ntfy down"); };
  assert.equal((await failing.post("/v1/feedback", { feedbackId: randomUUID(), kind: "like", message: "x" })).status, 201);
  await Promise.all(failing.waits);
});
