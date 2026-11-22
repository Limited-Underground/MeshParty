import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { test } from "node:test";
import worker from "../src/index.mjs";
import { createSqliteD1 } from "./sqlite-d1.mjs";

const endpoint = "https://reports.example.com/v1/feedback";
const sampleIp = "192.0.2.6";
const details = {
  versionName: "0.1.7",
  versionCode: 8,
  androidVersion: "16",
  manufacturer: "Example",
  model: "Demo 日本語 🧪",
  meshtasticConnected: true,
  meshCoreConnected: false,
};

function feedback(fields = {}) {
  return { feedbackId: randomUUID(), kind: "idea", message: "  Sample feedback 🌲\nIt works.  ", ...fields };
}

function setup(t) {
  const { db, binding } = createSqliteD1();
  t.after(() => db.close());
  const env = {
    REPORTS: binding,
    REPORT_LIMITER: { limit: async () => ({ success: true }) },
  };
  const headers = { "Content-Type": "application/json", "CF-Connecting-IP": sampleIp };
  const send = (draft, init = {}) => worker.fetch(new Request(endpoint, {
    method: "POST",
    headers,
    body: JSON.stringify(draft),
    ...init,
  }), env);
  const count = () => db.prepare("SELECT count(*) AS count FROM feedback").get().count;
  return { db, env, headers, send, count };
}

test("feedback receipt follows the insert and equivalent retries preserve the stored row", async t => {
  const { db, env, send, count } = setup(t);
  env.RETENTION_DAYS = "7";
  const draft = feedback({ details });
  const reorderedDetails = Object.fromEntries(Object.entries(details).reverse());
  const responses = await Promise.all([send(draft), send({ ...draft, details: reorderedDetails })]);
  assert.deepEqual(responses.map(response => response.status).sort(), [200, 201]);
  for (const response of responses) {
    assert.deepEqual(await response.json(), { receipt: draft.feedbackId });
    assert.equal(response.headers.get("cache-control"), "no-store");
  }

  const stored = () => db.prepare("SELECT * FROM feedback WHERE feedback_id = ?").get(draft.feedbackId);
  db.prepare("UPDATE feedback SET received_at = received_at - 60, expires_at = expires_at - 60 WHERE feedback_id = ?")
    .run(draft.feedbackId);
  const original = stored();
  assert.equal((await send(draft)).status, 200);
  assert.deepEqual(stored(), original);
  assert.equal(original.expires_at - original.received_at, 90 * 86_400);
  assert.equal(original.message, draft.message);
  assert.deepEqual(JSON.parse(original.details_json), details);
  assert.deepEqual(Object.keys(original), ["feedback_id", "kind", "message", "details_json", "received_at", "expires_at"]);

  for (const changed of [{ kind: "bug" }, { message: "different" }, { details: null }, {
    details: { ...details, meshCoreConnected: true },
  }]) {
    const conflict = await send({ ...draft, ...changed });
    assert.equal(conflict.status, 409);
    assert.equal(await conflict.text(), "");
  }
  assert.deepEqual(stored(), original);
  assert.equal(count(), 1);
});

test("all feedback kinds accept omitted optional details and treat explicit null as equivalent", async t => {
  const { db, send, count } = setup(t);
  for (const kind of ["like", "dislike", "change", "idea", "bug"]) {
    const draft = feedback({ kind });
    assert.equal((await send(draft)).status, 201);
    const retry = await send({ ...draft, details: null });
    assert.equal(retry.status, 200);
    assert.deepEqual(await retry.json(), { receipt: draft.feedbackId });
  }
  assert.equal(count(), 5);
  assert.equal(db.prepare("SELECT count(*) AS count FROM feedback WHERE details_json IS NULL").get().count, 5);
});

test("feedback refuses unknown fields, malformed Unicode and invalid detail types", async t => {
  const { send, count } = setup(t);
  for (const draft of [
    null,
    [],
    feedback({ feedbackId: "not-a-uuid" }),
    feedback({ kind: "report" }),
    feedback({ message: " \t\n" }),
    feedback({ message: "bad\ud800" }),
    feedback({ message: "🌲".repeat(1_000) + "a" }),
    feedback({ ip: sampleIp }),
    feedback({ details: "private" }),
    feedback({ details: [] }),
    feedback({ details: {} }),
    feedback({ details: { ...details, ip: sampleIp } }),
    feedback({ details: { ...details, versionName: " \n" } }),
    feedback({ details: { ...details, model: "a".repeat(129) } }),
    feedback({ details: { ...details, androidVersion: "\udc00" } }),
    feedback({ details: { ...details, versionCode: 0 } }),
    feedback({ details: { ...details, versionCode: 2_147_483_648 } }),
    feedback({ details: { ...details, versionCode: 1.5 } }),
    feedback({ details: { ...details, meshtasticConnected: "true" } }),
  ]) {
    assert.equal((await send(draft)).status, 400, JSON.stringify(draft).slice(0, 100));
  }
  assert.equal(count(), 0);
});

test("the feedback size cap permits maximum escaped strings and rejects oversized streams", async t => {
  const { send, headers, count } = setup(t);
  const detailText = "雪".repeat(128);
  const draft = feedback({ message: "雪".repeat(2_000), details: {
    ...details, versionName: detailText, androidVersion: detailText, manufacturer: detailText, model: detailText,
  } });
  const escaped = JSON.stringify(draft).replaceAll("雪", "\\u96ea");
  assert.ok(escaped.length > 15_000);
  assert.equal((await send(draft, { body: escaped })).status, 201);
  assert.equal((await send(draft, { headers: { ...headers, "Content-Length": "16385" } })).status, 413);
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new Uint8Array(16_384));
      controller.enqueue(new Uint8Array(1));
      controller.close();
    },
  });
  assert.equal((await send(draft, { body: stream, duplex: "half" })).status, 413);
  assert.equal((await send(draft, { body: new Uint8Array([0xc3, 0x28]) })).status, 400);
  assert.equal(count(), 1);
});

test("feedback is HTTPS write-only and uses its own hashed rate bucket", async t => {
  const { env, headers, send, count } = setup(t);
  const draft = feedback();
  const read = await worker.fetch(new Request(endpoint), env);
  assert.equal(read.status, 405);
  assert.equal(read.headers.get("allow"), "POST");
  assert.equal((await worker.fetch(new Request(endpoint + "/export"), env)).status, 404);
  assert.equal((await worker.fetch(new Request("http://reports.example.com/v1/feedback", { method: "POST" }), env)).status, 426);
  assert.equal((await send(draft, { headers: { ...headers, "Content-Type": "text/plain" } })).status, 415);
  assert.equal((await send(draft, { headers: { "Content-Type": "application/json" } })).status, 503);

  const limiterKeys = [];
  env.REPORT_LIMITER = { limit: async ({ key }) => {
    limiterKeys.push(key);
    return { success: !key.startsWith("feedback:") };
  } };
  const limited = await send(draft);
  assert.equal(limited.status, 429);
  assert.equal(limited.headers.get("retry-after"), "60");
  const abuseReport = await worker.fetch(new Request("https://reports.example.com/v1/reports", {
    method: "POST",
    headers,
    body: JSON.stringify({
      reportId: randomUUID(), kind: "message", reason: "spam", network: "meshcore", message: "Fictional report",
    }),
  }), env);
  assert.equal(abuseReport.status, 201);
  const expectedDigest = createHash("sha256").update(sampleIp).digest("hex");
  assert.deepEqual(limiterKeys, [`feedback:${expectedDigest}`, expectedDigest]);
  assert.equal(count(), 0);
});

test("feedback intake never claims success when storage fails or is unconfirmed", async t => {
  const { env, send, count } = setup(t);
  const storage = env.REPORTS;
  env.REPORTS = { ...storage, batch: async () => { throw new Error("fictional failure"); } };
  const failed = await send(feedback());
  assert.equal(failed.status, 503);
  assert.equal(await failed.text(), "");
  env.REPORTS = { ...storage, batch: async () => [{ success: true, meta: { changes: 1 } }, { success: false }] };
  assert.equal((await send(feedback())).status, 503);
  assert.equal(count(), 0);
});

test("the daily purge removes only expired feedback", async t => {
  const { db, env, send, count } = setup(t);
  const expired = feedback();
  const recent = feedback();
  assert.equal((await send(expired)).status, 201);
  assert.equal((await send(recent)).status, 201);
  db.prepare("UPDATE feedback SET received_at = unixepoch() - 7776000, expires_at = unixepoch() WHERE feedback_id = ?")
    .run(expired.feedbackId);
  await worker.scheduled({}, env);
  assert.equal(count(), 1);
  assert.equal(db.prepare("SELECT feedback_id FROM feedback").get().feedback_id, recent.feedbackId);
});
