package com.limitedunderground.meshinbox;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Sends only the evidence included in a user's reviewed report. Call submit off the UI thread. */
final class AbuseReports {
    enum Status { SENT, UNAVAILABLE, OFFLINE, REJECTED, FAILED }

    record Result(Status status, String receipt) {}

    private static final Set<String> REASONS = Set.of(
            "harassment", "threat", "hate", "sexual", "spam", "illegal", "privacy", "other");
    private static final Pattern REPORT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern MESHTASTIC_NODE = Pattern.compile("node:[0-9a-f]{8}");
    private static final Pattern MESHCORE_NODE = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_REQUEST_BYTES = 8192;
    private static final int MAX_RESPONSE_BYTES = 1024;
    private static final URI ENDPOINT = configuredEndpoint();

    private AbuseReports() {}

    static boolean isConfigured() {
        return ENDPOINT != null;
    }

    static Result submit(String reportId, String kind, String reason, String network,
                         String message, String senderId, String senderName) {
        if (!isConfigured()) return failure(Status.UNAVAILABLE);

        boolean meshcore = "meshcore".equals(network);
        boolean senderReport = "sender".equals(kind);
        if (reportId == null || !REPORT_ID.matcher(reportId).matches()
                || (!senderReport && !"message".equals(kind))
                || reason == null || !REASONS.contains(reason)
                || (!meshcore && !"meshtastic".equals(network))
                || (senderReport && senderId == null && senderName == null)
                || (!senderReport && message == null)
                || (message != null && !validText(message, 1024))
                || (senderId != null && !(meshcore ? MESHCORE_NODE : MESHTASTIC_NODE).matcher(senderId).matches())
                || (senderName != null && (!meshcore || senderId != null || !validText(senderName, 128)))) {
            return failure(Status.REJECTED);
        }

        HttpURLConnection connection = null;
        try {
            JSONObject report = new JSONObject()
                    .put("reportId", reportId)
                    .put("kind", kind)
                    .put("reason", reason)
                    .put("network", network)
                    .put("message", message)
                    .put("senderId", senderId)
                    .put("senderName", senderName);
            byte[] body = report.toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_REQUEST_BYTES) return failure(Status.REJECTED);

            connection = (HttpURLConnection) ENDPOINT.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int response = connection.getResponseCode();
            if (response == 200 || response == 201) {
                try (InputStream input = connection.getInputStream()) {
                    Object receipt = new JSONObject(readReceipt(input)).opt("receipt");
                    if (reportId.equals(receipt)) return new Result(Status.SENT, reportId);
                }
            }
            if (response >= 400 && response < 500 && response != 408 && response != 429) {
                return failure(Status.REJECTED);
            }
        } catch (UnknownHostException | ConnectException | NoRouteToHostException offline) {
            return failure(Status.OFFLINE);
        } catch (IOException | JSONException failure) {
            return failure(Status.FAILED);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return failure(Status.FAILED);
    }

    private static Result failure(Status status) {
        return new Result(status, null);
    }

    private static URI configuredEndpoint() {
        URI endpoint = URI.create(BuildConfig.ABUSE_REPORT_URL);
        boolean loopback = BuildConfig.DEBUG && "http".equals(endpoint.getScheme())
                && "127.0.0.1".equals(endpoint.getHost());
        if ((!"https".equals(endpoint.getScheme()) && !loopback)
                || endpoint.getHost() == null
                || !"/v1/reports".equals(endpoint.getRawPath())
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || endpoint.getPort() == 0
                || endpoint.getPort() > 65535) {
            return null;
        }
        return endpoint;
    }

    private static boolean validText(String value, int maxLength) {
        if (value.length() > maxLength || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) return false;
        return value.codePoints().anyMatch(codePoint -> !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint) && codePoint != '\uFEFF');
    }

    private static String readReceipt(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (response.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Report receipt is too large");
            response.write(buffer, 0, count);
        }
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.toByteArray())).toString();
    }
}
