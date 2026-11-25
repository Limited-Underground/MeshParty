package com.limitedunderground.meshinbox;

import com.limitedunderground.meshinbox.FeedbackEmail.Details;
import com.limitedunderground.meshinbox.FeedbackEmail.Kind;
import com.limitedunderground.meshinbox.FeedbackSubmission.Attempt;
import com.limitedunderground.meshinbox.FeedbackSubmission.Draft;

import java.net.URI;
import java.util.UUID;

public final class FeedbackSubmissionTest {
    private static final Details DETAILS = new Details("0.1.7", 8, "16", "Google", "Pixel 8", true, false);

    public static void main(String[] args) {
        retriesKeepTheReviewedId();
        editsNeverReplacePendingOrUnconfirmedAttempts();
        onlyOneRequestMayBeInFlight();
        successfulAcknowledgementClosesTheSubmission();
        sanitizesOnlyTheApprovedDetails();
        derivesOnlyAValidHttpsEndpoint();
        acceptsOnlyAMatchingSuccessfulReceipt();
        System.out.println("MeshParty direct feedback host checks passed");
    }

    private static void retriesKeepTheReviewedId() {
        FeedbackSubmission flow = new FeedbackSubmission();
        Draft draft = new Draft(Kind.IDEA, "  Add text filtering. ", DETAILS);
        Attempt first = flow.review(draft);
        require(first.id().version() == 4 && first.draft().message().equals("Add text filtering."),
                "review freezes the final message and generates a random receipt ID");
        require(!flow.needsEmailWarning(draft), "preview alone has not sent anything");
        require(flow.start(first), "initial confirmed payload should send");
        flow.finish(first, false, true);
        require(flow.needsEmailWarning(draft), "email needs an explicit warning after uncertain delivery");
        require(flow.review(draft) == first && flow.start(first), "retry must preserve the receipt and payload");
        flow.finish(first, false, false);
        require(flow.needsEmailWarning(draft), "later rejection does not establish that the first send failed");
    }

    private static void editsNeverReplacePendingOrUnconfirmedAttempts() {
        FeedbackSubmission flow = new FeedbackSubmission();
        Draft draft = new Draft(Kind.IDEA, "More contrast", DETAILS);
        Attempt first = flow.review(draft);
        require(flow.start(first), "first confirmed draft sends");
        flow.edit();
        require(flow.preview() == first, "editing cannot replace an in-flight attempt");
        flow.finish(first, false, true);
        flow.edit();
        Attempt changed = flow.review(new Draft(Kind.IDEA, "More contrast", null));
        require(!changed.id().equals(first.id()) && changed.draft().details() == null,
                "changed payload gets a new ID and details are absent when disabled");
        require(flow.needsEmailWarning(changed.draft()),
                "a metadata toggle does not make the same message safe to email twice");
        flow.edit();
        require(flow.review(draft) == first, "reverting exactly to a previous confirmed payload recovers its ID");
    }

    private static void onlyOneRequestMayBeInFlight() {
        FeedbackSubmission flow = new FeedbackSubmission();
        Attempt first = flow.review(new Draft(Kind.LIKE, "Nice colors", null));
        require(flow.start(first), "first tap should submit");
        require(!flow.start(first) && flow.isInFlight(), "duplicate tap must not start another submission");
        Attempt other = new Attempt(UUID.randomUUID(), first.draft());
        flow.finish(other, true, false);
        require(flow.isInFlight(), "unrelated completion cannot clear the pending attempt");
        flow.finish(first, false, false);
        require(!flow.isInFlight() && !flow.needsEmailWarning(first.draft()),
                "a definitive failure releases the submit guard without a duplicate warning");
    }

    private static void successfulAcknowledgementClosesTheSubmission() {
        FeedbackSubmission flow = new FeedbackSubmission();
        Attempt first = flow.review(new Draft(Kind.BUG, "Can't see the picker", null));
        require(flow.start(first), "reviewed draft must start");
        flow.finish(first, true, false);
        require(flow.isAccepted() && !flow.start(first), "accepted feedback cannot be sent twice");
    }

    private static void sanitizesOnlyTheApprovedDetails() {
        Details oversized = new Details("0.1.7", 8, " ", "x".repeat(127) + "🙂suffix",
                "Pixel 8", false, true);
        Draft draft = new Draft(Kind.BUG, "Message stays unchanged\nwith interior whitespace", oversized);
        require(draft.details().androidVersion().equals("Unknown") && draft.details().manufacturer().length() == 127,
                "detail strings must be usable and bounded without splitting surrogate pairs");
        require(!draft.details().meshtasticConnected() && draft.details().meshCoreConnected(),
                "radio flags must be independent");
        require(FeedbackSubmission.isValidMessage("x".repeat(2000)), "a 2,000-unit message must pass");
        require(!FeedbackSubmission.isValidMessage("x".repeat(2001))
                        && !FeedbackSubmission.isValidMessage("\uD800")
                        && !FeedbackSubmission.isValidMessage("\uFEFF")
                        && !FeedbackSubmission.isValidMessage(" \t\n"),
                "reject oversized, malformed and blank feedback");
    }

    private static void derivesOnlyAValidHttpsEndpoint() {
        require(URI.create("https://reports.example.invalid:443/v1/feedback").equals(
                        FeedbackClient.feedbackEndpoint("https://reports.example.invalid:443/v1/reports")),
                "feedback should use HTTPS on the same report origin");
        for (String invalid : new String[]{"", "http://127.0.0.1/v1/reports",
                "https://reports.example.invalid:65536/v1/reports", "https://host.invalid/v1/feedback",
                "https://host.invalid/v1/reports?query=1", "https://host.invalid/v1/reports#frag",
                "https://name@host.invalid/v1/reports"}) {
            require(FeedbackClient.feedbackEndpoint(invalid) == null,
                    "invalid or non-production report URL must not enable direct feedback");
        }
    }

    private static void acceptsOnlyAMatchingSuccessfulReceipt() {
        UUID expected = UUID.randomUUID();
        require(FeedbackClient.matchesReceipt(200, expected, expected.toString())
                        && FeedbackClient.matchesReceipt(201, expected, expected.toString()),
                "created and duplicate acknowledgements use the same ID");
        require(!FeedbackClient.matchesReceipt(204, expected, expected.toString())
                        && !FeedbackClient.matchesReceipt(200, expected, UUID.randomUUID().toString())
                        && !FeedbackClient.matchesReceipt(201, expected, null),
                "a response must confirm the exact durable receipt");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
