package com.limitedunderground.meshinbox;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Submit a reviewed feedback draft. Run on a background thread. */
final class FeedbackClient {
    enum Outcome { ACCEPTED, UNAVAILABLE, BUSY, OFFLINE, REJECTED, UNCONFIRMED }

    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final int MAX_RESPONSE_BYTES = 1_024;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final URI ENDPOINT = feedbackEndpoint(BuildConfig.ABUSE_REPORT_URL);
    // A destroyed Activity may have a request that is still completing.
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();

    private FeedbackClient() {}

    static boolean isConfigured() {
        return ENDPOINT != null;
    }

    static URI feedbackEndpoint(String reportUrl) {
        try {
            URI report = URI.create(reportUrl);
            if (!"https".equals(report.getScheme()) || report.getHost() == null
                    || !"/v1/reports".equals(report.getRawPath())
                    || report.getRawUserInfo() != null || report.getRawQuery() != null
                    || report.getRawFragment() != null || report.getPort() == 0 || report.getPort() > 65535) {
                return null;
            }
            return report.resolve("/v1/feedback");
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static Outcome submit(FeedbackSubmission.Attempt attempt) {
        if (!isConfigured()) return Outcome.UNAVAILABLE;
        if (!IN_FLIGHT.compareAndSet(false, true)) return Outcome.BUSY;
        HttpURLConnection connection = null;
        boolean requestMayHaveStarted = false;
        try {
            byte[] body = payload(attempt).toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_REQUEST_BYTES) return Outcome.REJECTED;

            connection = (HttpURLConnection) ENDPOINT.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(6_000);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                requestMayHaveStarted = true;
                output.write(body);
            }

            int response = connection.getResponseCode();
            if (response == 200 || response == 201) {
                try (InputStream input = connection.getInputStream()) {
                    return matchesReceipt(response, attempt.id(), readReceipt(input))
                            ? Outcome.ACCEPTED : Outcome.UNCONFIRMED;
                }
            }
            if (response == 400 || response == 413 || response == 415 || response == 422 || response == 429) {
                return Outcome.REJECTED;
            }
            return Outcome.UNCONFIRMED;
        } catch (UnknownHostException | ConnectException | NoRouteToHostException problem) {
            return requestMayHaveStarted ? Outcome.UNCONFIRMED : Outcome.OFFLINE;
        } catch (IOException | JSONException | RuntimeException problem) {
            return requestMayHaveStarted ? Outcome.UNCONFIRMED : Outcome.REJECTED;
        } finally {
            try {
                if (connection != null) connection.disconnect();
            } catch (RuntimeException ignored) {
                // Connection cleanup cannot change the submission result.
            } finally {
                IN_FLIGHT.set(false);
            }
        }
    }

    static boolean matchesReceipt(int status, UUID expected, Object receipt) {
        return (status == 200 || status == 201) && expected.toString().equals(receipt);
    }

    static JSONObject payload(FeedbackSubmission.Attempt attempt) throws JSONException {
        FeedbackSubmission.Draft draft = attempt.draft();
        JSONObject body = new JSONObject()
                .put("feedbackId", attempt.id().toString())
                .put("kind", draft.kind().name().toLowerCase(Locale.ROOT))
                .put("message", draft.message());
        FeedbackEmail.Details details = draft.details();
        if (details != null) {
            body.put("details", new JSONObject()
                    .put("versionName", details.versionName())
                    .put("versionCode", details.versionCode())
                    .put("androidVersion", details.androidVersion())
                    .put("manufacturer", details.manufacturer())
                    .put("model", details.model())
                    .put("meshtasticConnected", details.meshtasticConnected())
                    .put("meshCoreConnected", details.meshCoreConnected()));
        }
        return body;
    }

    private static Object readReceipt(InputStream input) throws IOException, JSONException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        long started = System.nanoTime();
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (System.nanoTime() - started > TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Feedback receipt timed out");
            }
            if (response.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Feedback receipt is too large");
            response.write(buffer, 0, count);
        }
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.toByteArray())).toString();
        JSONTokener json = new JSONTokener(text);
        if (json.nextValue() instanceof JSONObject receipt && receipt.length() == 1 && json.nextClean() == '\0') {
            return receipt.opt("receipt");
        }
        return null;
    }
}
