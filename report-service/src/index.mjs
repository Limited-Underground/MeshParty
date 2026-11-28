const MAX_REPORT_REQUEST_BYTES = 8_192;
// Allow 2,000 message units and four 128-unit detail strings even as JSON escapes.
const MAX_FEEDBACK_REQUEST_BYTES = 16_384;
const MAX_MESSAGE_LENGTH = 1_024;
const MAX_SENDER_NAME_LENGTH = 128;
const MAX_FEEDBACK_MESSAGE_LENGTH = 2_000;
const MAX_FEEDBACK_DETAIL_LENGTH = 128;
const SECONDS_PER_DAY = 86_400;
const FEEDBACK_RETENTION_DAYS = 90;
const KINDS = new Set(["message", "sender"]);
const NETWORKS = new Set(["meshcore", "meshtastic"]);
const REASONS = new Set([
  "harassment", "threat", "hate", "sexual", "spam", "illegal", "privacy", "other",
]);
const FIELDS = new Set(["reportId", "kind", "reason", "network", "message", "senderId", "senderName"]);
const FEEDBACK_KINDS = new Set(["like", "dislike", "change", "idea", "bug"]);
const FEEDBACK_FIELDS = new Set(["feedbackId", "kind", "message", "details"]);
const DETAIL_FIELDS = new Set([
  "versionName", "versionCode", "androidVersion", "manufacturer", "model", "meshtasticConnected", "meshCoreConnected",
]);
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const MESHTASTIC_ID = /^node:[0-9a-f]{8}$/;
const MESHCORE_ID = /^[0-9a-f]{64}$/;
const responseHeaders = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };

const insertReport =
  "INSERT INTO abuse_reports (report_id, kind, reason, network, message, sender_id, sender_name, expires_at) " +
  "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, unixepoch() + ?8) ON CONFLICT (report_id) DO NOTHING";
const selectReport =
  "SELECT kind, reason, network, message, sender_id AS senderId, sender_name AS senderName " +
  "FROM abuse_reports WHERE report_id = ?1";
const insertFeedback =
  "INSERT INTO feedback (feedback_id, kind, message, details_json, expires_at) " +
  "VALUES (?1, ?2, ?3, ?4, unixepoch() + ?5) ON CONFLICT (feedback_id) DO NOTHING";
const selectFeedback =
  "SELECT kind, message, details_json AS details FROM feedback WHERE feedback_id = ?1";

export default {
  async fetch(request, env, ctx) {
    const response = await handle(request, env);
    // A new submission (201, not a retry) sends the owner a push without its content (D-208).
    if (response.status === 201 && response.alert) {
      const sent = sendAlert(env.ALERT_URL, response.alert);
      if (ctx?.waitUntil) ctx.waitUntil(sent); else await sent;
    }
    return response;
  },

  async scheduled(_controller, env) {
    // Keep the two purges independent so one failure can't leave both queues unpurged.
    const purges = await Promise.allSettled([
      deleteExpired(env.REPORTS, "DELETE FROM abuse_reports WHERE expires_at <= unixepoch()"),
      deleteExpired(env.REPORTS, "DELETE FROM feedback WHERE expires_at <= unixepoch()"),
    ]);
    if (purges.some(purge => purge.status !== "fulfilled" || !purge.value?.success)) {
      throw new Error("Report service expiry failed");
    }
  },
};

/**
 * Posts a short notice to the owner's private ntfy topic (ALERT_URL, a Worker secret).
 * Only the submission type and its fixed category go out, never the text, names or IDs.
 * A failed or missing alert never changes the response the app receives.
 */
async function sendAlert(alertUrl, alert) {
  if (typeof alertUrl !== "string" || !alertUrl.startsWith("https://")) return;
  try {
    await fetch(alertUrl, {
      method: "POST",
      headers: { Title: alert.title, Tags: alert.tags, Priority: "default" },
      body: alert.body,
    });
  } catch {
    // The submission is already stored; the owner still finds it in the database.
  }
}

async function handle(request, env) {
  {
    const url = new URL(request.url);
    const isFeedback = url.pathname === "/v1/feedback";
    if (!isFeedback && url.pathname !== "/v1/reports") return empty(404);
    if (request.method !== "POST") return empty(405, { Allow: "POST" });
    if (url.protocol !== "https:") return empty(426);
    const contentType = request.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase();
    if (contentType !== "application/json") return empty(415);
    const maxBytes = isFeedback ? MAX_FEEDBACK_REQUEST_BYTES : MAX_REPORT_REQUEST_BYTES;
    if (Number(request.headers.get("Content-Length")) > maxBytes) return empty(413);

    const connectingIp = request.headers.get("CF-Connecting-IP");
    const retentionDays = isFeedback ? FEEDBACK_RETENTION_DAYS : Number(env.RETENTION_DAYS ?? 30);
    if (!connectingIp || !env.REPORT_LIMITER || !env.REPORTS ||
        !Number.isInteger(retentionDays) || retentionDays < 1 || retentionDays > 90) {
      return empty(503);
    }
    try {
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(connectingIp));
      const key = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
      const { success } = await env.REPORT_LIMITER.limit({ key: isFeedback ? `feedback:${key}` : key });
      if (success === false) return empty(429, { "Retry-After": "60" });
      if (success !== true) return empty(503);
    } catch {
      return empty(503);
    }

    const body = await readJsonBody(request.body, maxBytes);
    if (body.status !== 200) return empty(body.status);
    if (isFeedback) {
      const response = await receiveFeedback(body.value, env.REPORTS);
      if (response.status !== 201) return response;
      response.alert = {
        title: "MeshParty feedback",
        tags: "speech_balloon",
        body: `New feedback: ${body.value.kind}. Read it in the D1 console.`,
      };
      return response;
    }
    const report = validateReport(body.value);
    if (report === null) return empty(400);

    try {
      // The transaction reads its own insert and keeps concurrent retries from replacing evidence.
      const [write, read] = await env.REPORTS.batch([
        env.REPORTS.prepare(insertReport).bind(
          report.reportId, report.kind, report.reason, report.network, report.message,
          report.senderId, report.senderName, retentionDays * SECONDS_PER_DAY,
        ),
        env.REPORTS.prepare(selectReport).bind(report.reportId),
      ]);
      const response = acknowledge(write, read, report.reportId, report);
      response.alert = {
        title: "MeshParty abuse report",
        tags: "warning",
        body: `New ${report.kind} report: ${report.reason}, ${report.network}. Review it in the D1 console.`,
      };
      return response;
    } catch {
      return empty(503);
    }
  }
}

function empty(status, headers = {}) {
  return new Response(null, { status, headers: { ...responseHeaders, ...headers } });
}

async function deleteExpired(database, sql) {
  return database.prepare(sql).run();
}

function acknowledge(write, read, id, submission) {
  if (!write?.success || !read?.success || read.results?.length !== 1 ||
      ![0, 1].includes(write.meta?.changes)) {
    return empty(503);
  }
  const stored = read.results[0];
  if (Object.keys(stored).some(key => stored[key] !== submission[key])) return empty(409);
  return Response.json({ receipt: id }, {
    status: write.meta.changes === 1 ? 201 : 200,
    headers: responseHeaders,
  });
}

async function receiveFeedback(value, database) {
  const feedback = validateFeedback(value);
  if (feedback === null) return empty(400);
  const { feedbackId, kind, message, details } = feedback;
  try {
    const [write, read] = await database.batch([
      database.prepare(insertFeedback).bind(feedbackId, kind, message, details, FEEDBACK_RETENTION_DAYS * SECONDS_PER_DAY),
      database.prepare(selectFeedback).bind(feedbackId),
    ]);
    return acknowledge(write, read, feedbackId, feedback);
  } catch {
    return empty(503);
  }
}

async function readJsonBody(body, maxBytes) {
  if (!body) return { status: 400 };
  const data = new Uint8Array(maxBytes);
  let length = 0;
  try {
    for await (const chunk of body) {
      if (chunk.byteLength > data.length - length) return { status: 413 };
      data.set(chunk, length);
      length += chunk.byteLength;
    }
    const value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(data.subarray(0, length)));
    return { status: 200, value };
  } catch {
    return { status: 400 };
  }
}

function validateReport(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).some(key => !FIELDS.has(key))) {
    return null;
  }
  const { reportId, kind, reason, network } = value;
  if (typeof reportId !== "string" || !UUID_V4.test(reportId) ||
      !KINDS.has(kind) || !REASONS.has(reason) || !NETWORKS.has(network)) {
    return null;
  }

  const message = value.message ?? null;
  const senderId = value.senderId ?? null;
  const senderName = value.senderName ?? null;
  const validId = network === "meshtastic" ? MESHTASTIC_ID : MESHCORE_ID;
  if ((message !== null && !validText(message, MAX_MESSAGE_LENGTH)) ||
      (senderId !== null && (typeof senderId !== "string" || !validId.test(senderId))) ||
      (senderName !== null && (network !== "meshcore" || !validText(senderName, MAX_SENDER_NAME_LENGTH))) ||
      (senderId !== null && senderName !== null) ||
      (kind === "message" && message === null) ||
      (kind === "sender" && senderId === null && senderName === null)) {
    return null;
  }
  return { reportId, kind, reason, network, message, senderId, senderName };
}

function validateFeedback(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).some(key => !FEEDBACK_FIELDS.has(key))) {
    return null;
  }
  const { feedbackId, kind, message } = value;
  if (typeof feedbackId !== "string" || !UUID_V4.test(feedbackId) ||
      !FEEDBACK_KINDS.has(kind) || !validText(message, MAX_FEEDBACK_MESSAGE_LENGTH)) {
    return null;
  }
  if (value.details == null) return { feedbackId, kind, message, details: null };
  const details = value.details;
  if (typeof details !== "object" || Array.isArray(details) || Object.keys(details).some(key => !DETAIL_FIELDS.has(key))) {
    return null;
  }
  const { versionName, versionCode, androidVersion, manufacturer, model, meshtasticConnected, meshCoreConnected } = details;
  if (![versionName, androidVersion, manufacturer, model].every(text => validText(text, MAX_FEEDBACK_DETAIL_LENGTH)) ||
      !Number.isInteger(versionCode) || versionCode < 1 || versionCode > 2_147_483_647 ||
      typeof meshtasticConnected !== "boolean" || typeof meshCoreConnected !== "boolean") {
    return null;
  }
  // Normalize object member order so equivalent retries compare identically in D1.
  return { feedbackId, kind, message, details: JSON.stringify({
    versionName, versionCode, androidVersion, manufacturer, model, meshtasticConnected, meshCoreConnected,
  }) };
}

function validText(text, maxLength) {
  return typeof text === "string" && text.length <= maxLength && text.trim() !== "" && text.isWellFormed();
}
