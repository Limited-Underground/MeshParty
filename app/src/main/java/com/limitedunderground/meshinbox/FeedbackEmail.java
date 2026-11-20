package com.limitedunderground.meshinbox;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class FeedbackEmail {
    static final String ADDRESS = "admin@limitedunderground.com";
    static final int MAX_MESSAGE_LENGTH = 2000;
    private static final String NEWLINE = "\r\n";

    enum Kind {
        LIKE("Like"), DISLIKE("Dislike"), CHANGE("Change"), IDEA("Idea"), BUG("Bug");

        final String subjectLabel;

        Kind(String subjectLabel) {
            this.subjectLabel = subjectLabel;
        }
    }

    record Details(String versionName, int versionCode, String androidVersion, String manufacturer,
                   String model, boolean meshtasticConnected, boolean meshCoreConnected) {}

    private FeedbackEmail() {}

    static boolean isValidMessage(String message) {
        return message != null && message.length() <= MAX_MESSAGE_LENGTH
                && message.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c));
    }

    static String mailto(Kind kind, String message, Details details) {
        if (kind == null || !isValidMessage(message)) {
            throw new IllegalArgumentException("Feedback requires a kind and a message within the length limit");
        }
        String subject = "MeshParty feedback (" + kind.subjectLabel + ")";
        String body = message.trim().replace("\r\n", "\n").replace('\r', '\n').replace("\n", NEWLINE);
        if (details != null) {
            subject += " " + details.versionName();
            body += NEWLINE + NEWLINE + String.join(NEWLINE,
                    "App and phone details",
                    "App version: " + details.versionName() + " (" + details.versionCode() + ")",
                    "Android: " + details.androidVersion(),
                    "Phone: " + details.manufacturer() + " " + details.model(),
                    "Meshtastic connected: " + (details.meshtasticConnected() ? "Yes" : "No"),
                    "MeshCore connected: " + (details.meshCoreConnected() ? "Yes" : "No"));
        }
        return "mailto:" + ADDRESS + "?subject=" + encode(subject) + "&body=" + encode(body);
    }

    private static String encode(String value) {
        try {
            // Mailto query fields use percent-encoded spaces, not form-encoded plus signs.
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
