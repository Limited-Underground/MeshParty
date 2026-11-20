const MAX_REQUEST_BYTES = 8_192;
const MAX_MESSAGE_LENGTH = 1_024;
const MAX_SENDER_NAME_LENGTH = 128;
const SECONDS_PER_DAY = 86_400;
const KINDS = new Set(["message", "sender"]);
const NETWORKS = new Set(["meshcore", "meshtastic"]);
const REASONS = new Set([
  "harassment", "threat", "hate", "sexual", "spam", "illegal", "privacy", "other",
]);
const FIELDS = new Set(["reportId", "kind", "reason", "network", "message", "senderId", "senderName"]);
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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/v1/reports") return empty(404);
    if (request.method !== "POST") return empty(405, { Allow: "POST" });
    if (url.protocol !== "https:") return empty(426);
    const contentType = request.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase();
    if (contentType !== "application/json") return empty(415);
    if (Number(request.headers.get("Content-Length")) > MAX_REQUEST_BYTES) return empty(413);

    const connectingIp = request.headers.get("CF-Connecting-IP");
    const retentionDays = Number(env.RETENTION_DAYS ?? 30);
    if (!connectingIp || !env.REPORT_LIMITER || !env.REPORTS ||
        !Number.isInteger(retentionDays) || retentionDays < 1 || retentionDays > 90) {
      return empty(503);
    }
    try {
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(connectingIp));
      const key = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
      const { success } = await env.REPORT_LIMITER.limit({ key });
      if (success === false) return empty(429, { "Retry-After": "60" });
      if (success !== true) return empty(503);
    } catch {
      return empty(503);
    }

    const body = await readReportBody(request.body);
    if (body.status !== 200) return empty(body.status);
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
      if (!write?.success || !read?.success || read.results?.length !== 1 ||
          ![0, 1].includes(write.meta?.changes)) {
        return empty(503);
      }
      const stored = read.results[0];
      if (Object.keys(stored).some(key => stored[key] !== report[key])) return empty(409);
      return Response.json({ receipt: report.reportId }, {
        status: write.meta.changes === 1 ? 201 : 200,
        headers: responseHeaders,
      });
    } catch {
      return empty(503);
    }
  },

  async scheduled(_controller, env) {
    try {
      const result = await env.REPORTS.prepare("DELETE FROM abuse_reports WHERE expires_at <= unixepoch()").run();
      if (result.success) return;
    } catch {
      // Report the job failure without logging statement results or user data.
    }
    throw new Error("Abuse report expiry failed");
  },
};

function empty(status, headers = {}) {
  return new Response(null, { status, headers: { ...responseHeaders, ...headers } });
}

async function readReportBody(body) {
  if (!body) return { status: 400 };
  const data = new Uint8Array(MAX_REQUEST_BYTES);
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

function validText(text, maxLength) {
  return typeof text === "string" && text.length <= maxLength && text.trim() !== "" && text.isWellFormed();
}
