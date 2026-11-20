package com.limitedunderground.meshinbox;

import com.limitedunderground.meshinbox.FeedbackEmail.Details;
import com.limitedunderground.meshinbox.FeedbackEmail.Kind;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class FeedbackEmailTest {
    private static final String RECIPIENT = "admin@limitedunderground.com";
    private static final Details SAMPLE = new Details("0.1.6", 7, "16", "Google", "Pixel 8", true, false);

    public static void main(String[] args) {
        includesOnlyTheRequestedDetails();
        omitsDetailsFromBothSubjectAndBody();
        encodesTheMessageWithoutAddingMailHeaders();
        validatesRequiredMessageAndLength();
        System.out.println("MeshParty feedback email host checks passed");
    }

    private static void includesOnlyTheRequestedDetails() {
        Draft draft = parse(FeedbackEmail.mailto(Kind.IDEA, "  Add a compact inbox.  ", SAMPLE));
        require(draft.subject().equals("MeshParty feedback (Idea) 0.1.6"), "incorrect subject");
        String expected = String.join("\r\n",
                "Add a compact inbox.", "",
                "App and phone details",
                "App version: 0.1.6 (7)",
                "Android: 16",
                "Phone: Google Pixel 8",
                "Meshtastic connected: Yes",
                "MeshCore connected: No");
        require(draft.body().equals(expected), "details must contain only the allowlisted fields");

        Details otherRadio = new Details("0.1.6", 7, "16", "Google", "Pixel 8", false, true);
        Draft otherDraft = parse(FeedbackEmail.mailto(Kind.IDEA, "Radio feedback", otherRadio));
        require(otherDraft.body().endsWith("Meshtastic connected: No\r\nMeshCore connected: Yes"),
                "radio types must be reported independently");
    }

    private static void omitsDetailsFromBothSubjectAndBody() {
        Draft draft = parse(FeedbackEmail.mailto(Kind.IDEA, "Add a compact inbox.", null));
        require(draft.subject().equals("MeshParty feedback (Idea)"), "subject must omit version when disabled");
        require(draft.body().equals("Add a compact inbox."), "body must omit all details when disabled");
    }

    private static void encodesTheMessageWithoutAddingMailHeaders() {
        String message = "Français \uD83D\uDE42 & +50% #? = yes\n&bcc=person@example.invalid\rLast line";
        String mailto = FeedbackEmail.mailto(Kind.BUG, message, null);
        Draft draft = parse(mailto);
        require(draft.body().equals(
                "Français \uD83D\uDE42 & +50% #? = yes\r\n&bcc=person@example.invalid\r\nLast line"),
                "Unicode and punctuation should round-trip with mailto line breaks");
        require(mailto.contains("%0D%0A") && mailto.contains("%20") && !mailto.contains("+"),
                "mail URI must percent-encode body line breaks and spaces");
    }

    private static void validatesRequiredMessageAndLength() {
        int limit = FeedbackEmail.MAX_MESSAGE_LENGTH;
        String atLimit = "a".repeat(limit);
        require(limit == 2000, "expected the 2,000-character limit");
        require(FeedbackEmail.isValidMessage(atLimit), "message at limit must be accepted");
        require(parse(FeedbackEmail.mailto(Kind.LIKE, atLimit, null)).body().equals(atLimit),
                "valid message at limit must not be truncated");
        rejectMessage(null);
        rejectMessage("");
        rejectMessage(" \t\n");
        rejectMessage(atLimit + "a");
        rejectMessage(" " + atLimit);
    }

    private static void rejectMessage(String message) {
        require(!FeedbackEmail.isValidMessage(message), "invalid message passed validation");
        try {
            FeedbackEmail.mailto(Kind.LIKE, message, null);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid message yielded a mail URI");
    }

    private static Draft parse(String mailto) {
        URI uri = URI.create(mailto);
        require("mailto".equals(uri.getScheme()) && uri.getRawFragment() == null, "expected a mailto URI");
        String prefix = RECIPIENT + "?";
        String addressAndHeaders = uri.getRawSchemeSpecificPart();
        require(addressAndHeaders.startsWith(prefix), "wrong recipient");
        String[] headers = addressAndHeaders.substring(prefix.length()).split("&", -1);
        require(headers.length == 2 && headers[0].startsWith("subject=") && headers[1].startsWith("body="),
                "expected only a subject and body");
        return new Draft(URLDecoder.decode(headers[0].substring("subject=".length()), StandardCharsets.UTF_8),
                URLDecoder.decode(headers[1].substring("body=".length()), StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Draft(String subject, String body) {}
}
