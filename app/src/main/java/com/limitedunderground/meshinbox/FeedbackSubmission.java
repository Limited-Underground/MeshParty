package com.limitedunderground.meshinbox;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Keeps reviewed payloads and receipt IDs in one open feedback dialog. */
final class FeedbackSubmission {
    private static final int DETAIL_LIMIT = 128;

    record Draft(FeedbackEmail.Kind kind, String message, FeedbackEmail.Details details) {
        Draft {
            if (kind == null || !isValidMessage(message)) {
                throw new IllegalArgumentException("Feedback requires a kind and a valid message");
            }
            message = message.trim();
            if (details != null) {
                if (details.versionCode() < 1) throw new IllegalArgumentException("Invalid version code");
                details = new FeedbackEmail.Details(
                        limitDetail(details.versionName()), details.versionCode(),
                        limitDetail(details.androidVersion()), limitDetail(details.manufacturer()),
                        limitDetail(details.model()), details.meshtasticConnected(), details.meshCoreConnected());
            }
        }
    }

    record Attempt(UUID id, Draft draft) {}

    private final Map<Draft, Attempt> submitted = new HashMap<>();
    private final Set<Attempt> unconfirmed = new HashSet<>();
    private Attempt preview;
    private Attempt inFlight;
    private boolean accepted;

    static boolean isValidMessage(String message) {
        return FeedbackEmail.isValidMessage(message) && message.trim().codePoints().anyMatch(c ->
                !Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != 0xfeff)
                && StandardCharsets.UTF_8.newEncoder().canEncode(message);
    }

    private static String limitDetail(String input) {
        if (input == null || !FeedbackEmail.isValidMessage(input)) return "Unknown";
        String value = input.trim();
        if (value.length() > DETAIL_LIMIT) {
            int limit = Character.isHighSurrogate(value.charAt(DETAIL_LIMIT - 1)) ? DETAIL_LIMIT - 1 : DETAIL_LIMIT;
            value = value.substring(0, limit);
        }
        return isValidMessage(value) ? value : "Unknown";
    }

    Attempt review(Draft draft) {
        if (inFlight != null || accepted) throw new IllegalStateException("Feedback is in progress or sent");
        if (preview != null && preview.draft().equals(draft)) return preview;
        preview = submitted.get(draft);
        if (preview == null) preview = new Attempt(UUID.randomUUID(), draft);
        return preview;
    }

    void edit() {
        if (inFlight == null && !accepted) preview = null;
    }

    boolean start(Attempt attempt) {
        if (attempt == null || attempt != preview || inFlight != null || accepted) return false;
        submitted.put(attempt.draft(), attempt);
        inFlight = attempt;
        return true;
    }

    void finish(Attempt attempt, boolean delivered, boolean deliveryUncertain) {
        if (attempt == null || attempt != inFlight) return;
        inFlight = null;
        accepted = delivered;
        if (delivered) unconfirmed.remove(attempt);
        else if (deliveryUncertain) unconfirmed.add(attempt);
    }

    boolean isInFlight() {
        return inFlight != null;
    }

    boolean isAccepted() {
        return accepted;
    }

    Attempt preview() {
        return preview;
    }

    boolean needsEmailWarning(Draft draft) {
        return unconfirmed.stream().anyMatch(previous -> previous.draft().kind() == draft.kind()
                && previous.draft().message().equals(draft.message()));
    }
}
