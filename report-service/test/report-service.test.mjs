import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import worker from "../src/index.mjs";
import { createSqliteD1 } from "./sqlite-d1.mjs";

const endpoint = "https://reports.example.com/v1/reports";
const senderKey = "a".repeat(64);
const fixtureMessage = "  Fictional sample \uD83C\uDF32\nO'Brien  ";

function report(fields = {}) {
  return {
    reportId: randomUUID(),
    kind: "message",
    reason: "spam",
    network: "meshcore",
    message: fixtureMessage,
    ...fields,
  };
}

function setup(t) {
  const { db, binding } = createSqliteD1();
  t.after(() => db.close());
  const env = {
    REPORTS: binding,
    REPORT_LIMITER: { limit: async () => ({ success: true }) },
  };
  const headers = { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.6" };
  const send = (body, init = {}) => worker.fetch(new Request(endpoint, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    ...init,
  }), env);
  const count = () => db.prepare("SELECT COUNT(*) AS count FROM abuse_reports").get().count;
  return { db, env, headers, send, count };
}

test("receipts follow the durable insert, with identical retries and changed payload conflicts", async t => {
  const { db, send, count } = setup(t);
  const draft = report({ senderName: "Example Channel Sender" });
  const [created, replay] = await Promise.all([send(draft), send(draft)]);
  assert.deepEqual([created.status, replay.status].sort(), [200, 201]);
  for (const response of [created, replay]) {
    assert.deepEqual(await response.json(), { receipt: draft.reportId });
    assert.equal(response.headers.get("cache-control"), "no-store");
  }
  const conflict = await send({ ...draft, message: "different text" });
  assert.equal(conflict.status, 409);
  assert.equal(await conflict.text(), "");
  assert.equal(count(), 1);
  const row = db.prepare("SELECT * FROM abuse_reports").get();
  assert.equal(row.message, fixtureMessage);
  assert.equal(row.sender_name, draft.senderName);
  assert.equal(row.sender_id, null);
  assert.equal(row.expires_at - row.received_at, 30 * 86_400);
  assert.deepEqual(Object.keys(row), [
    "report_id", "kind", "reason", "network", "message", "sender_id", "sender_name", "received_at", "expires_at",
  ]);
});

test("sender reports accept network-specific identities and reserve names for MeshCore", async t => {
  const { db, send, count } = setup(t);
  for (const draft of [
    report({ kind: "sender", message: null, senderId: senderKey }),
    report({ kind: "sender", message: null, network: "meshtastic", senderId: "node:12ab00ff" }),
    report({ kind: "sender", message: null, senderName: "Demo e\u0301" }),
  ]) {
    const response = await send(draft);
    assert.equal(response.status, 201);
    assert.deepEqual(await response.json(), { receipt: draft.reportId });
  }
  assert.equal(count(), 3);
  assert.equal(db.prepare("SELECT sender_name FROM abuse_reports WHERE sender_name IS NOT NULL").get().sender_name, "Demo e\u0301");
});

test("validation prevents extraneous data, invalid types, and missing reviewed evidence", async t => {
  const { send, count } = setup(t);
  for (const draft of [
    report({ bluetoothAddress: "02:00:00:00:00:01" }),
    report({ ip: "192.0.2.1" }),
    report({ diagnostics: [] }),
    report({ reportId: "not-a-uuid" }),
    report({ reason: "custom" }),
    report({ network: "unknown" }),
    report({ message: "" }),
    report({ message: " ".repeat(3) }),
    report({ message: "\ud800" }),
    report({ message: "x".repeat(1025) }),
    report({ senderName: "x".repeat(129) }),
    report({ senderId: "node:11223344" }),
    report({ senderId: senderKey + "\n" }),
    report({ senderId: senderKey, senderName: "Example Sender" }),
    report({ kind: "sender" }),
    report({ kind: "sender", message: null, senderName: "  " }),
    report({ kind: "sender", network: "meshtastic", senderName: "Example Sender" }),
  ]) {
    assert.equal((await send(draft)).status, 400, JSON.stringify(draft));
  }
  assert.equal(count(), 0);
});

test("body bounds cover both declared sizes and chunked bytes without trusting Content-Length", async t => {
  const { send, headers, count } = setup(t);
  const oversize = await send(report(), { headers: { ...headers, "Content-Length": "8193" } });
  assert.equal(oversize.status, 413);
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new Uint8Array(8192));
      controller.enqueue(new Uint8Array(1));
      controller.close();
    },
  });
  const chunked = await send(report(), { body: stream, duplex: "half" });
  assert.equal(chunked.status, 413);
  assert.equal((await send(report(), { body: new Uint8Array([0xc3, 0x28]) })).status, 400);
  assert.equal(count(), 0);
});

test("only HTTPS JSON intake is exposed and failures do not claim delivery", async t => {
  const { env, headers, send, count } = setup(t);
  const read = await worker.fetch(new Request(endpoint), env);
  assert.equal(read.status, 405);
  assert.equal(read.headers.get("allow"), "POST");
  assert.equal((await worker.fetch(new Request("https://reports.example.com/admin"), env)).status, 404);
  assert.equal((await worker.fetch(new Request("http://reports.example.com/v1/reports", { method: "POST" }), env)).status, 426);
  assert.equal((await send(report(), { headers: { ...headers, "Content-Type": "text/plain" } })).status, 415);
  assert.equal((await send(report(), { headers: { "Content-Type": "application/json" } })).status, 503);
  let rateKey;
  env.REPORT_LIMITER = { limit: async ({ key }) => {
    rateKey = key;
    return { success: false };
  } };
  const limited = await send(report());
  assert.equal(limited.status, 429);
  assert.equal(limited.headers.get("retry-after"), "60");
  assert.match(rateKey, /^[0-9a-f]{64}$/);
  env.REPORT_LIMITER = { limit: async () => ({ success: true }) };
  const storage = env.REPORTS;
  env.REPORTS = { ...storage, batch: async () => { throw new Error("fictional database error"); } };
  const unavailable = await send(report());
  assert.equal(unavailable.status, 503);
  assert.equal(await unavailable.text(), "");
  assert.equal(count(), 0);
});

test("the scheduled handler deletes expired reports and leaves recent reports available", async t => {
  const { db, env, send, count } = setup(t);
  env.RETENTION_DAYS = "7";
  const expired = report();
  const recent = report();
  assert.equal((await send(expired)).status, 201);
  assert.equal((await send(recent)).status, 201);
  const stored = db.prepare("SELECT received_at, expires_at FROM abuse_reports WHERE report_id = ?").get(recent.reportId);
  assert.equal(stored.expires_at - stored.received_at, 7 * 86_400);
  db.prepare("UPDATE abuse_reports SET received_at = unixepoch() - 8, expires_at = unixepoch() - 1 WHERE report_id = ?").run(expired.reportId);
  await worker.scheduled({}, env);
  assert.equal(count(), 1);
  assert.equal(db.prepare("SELECT report_id FROM abuse_reports").get().report_id, recent.reportId);
});
