package com.limitedunderground.meshinbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Supplier;

final class FeedbackDialog {
    private final Activity activity;
    private final Supplier<FeedbackEmail.Details> details;
    private final FeedbackSubmission submission = new FeedbackSubmission();
    private final boolean directAvailable = FeedbackClient.isConfigured();
    private final AlertDialog dialog;
    private final ScrollView scroll;
    private final LinearLayout form;
    private final RadioGroup categories;
    private final EditText message;
    private final Switch includeDetails;
    private final TextView preview;
    private final TextView status;
    private final Button email;
    private final LinearLayout fallback;
    private boolean emailWarning;

    static AlertDialog create(Activity activity, Supplier<FeedbackEmail.Details> details) {
        return new FeedbackDialog(activity, details).dialog;
    }

    private FeedbackDialog(Activity activity, Supplier<FeedbackEmail.Details> details) {
        this.activity = activity;
        this.details = details;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        Context context = builder.getContext();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * context.getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, inset / 2);
        content.setSaveFromParentEnabled(false);
        form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        content.addView(form);

        addText(form, R.string.feedback_intro);
        categories = new RadioGroup(context);
        addKind(categories, FeedbackEmail.Kind.LIKE, R.string.feedback_like);
        addKind(categories, FeedbackEmail.Kind.DISLIKE, R.string.feedback_dislike);
        addKind(categories, FeedbackEmail.Kind.CHANGE, R.string.feedback_change);
        addKind(categories, FeedbackEmail.Kind.IDEA, R.string.feedback_idea);
        addKind(categories, FeedbackEmail.Kind.BUG, R.string.feedback_bug);
        form.addView(categories);

        message = new EditText(context);
        int limit = FeedbackEmail.MAX_MESSAGE_LENGTH;
        message.setHint(context.getResources().getQuantityString(R.plurals.feedback_message, limit, limit));
        message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message.setGravity(Gravity.TOP | Gravity.START);
        message.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        message.setMinLines(3);
        message.setMaxLines(6);
        message.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        message.setSaveEnabled(false);
        message.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        message.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        form.addView(message, new LinearLayout.LayoutParams(-1, -2));

        includeDetails = new Switch(context);
        includeDetails.setText(R.string.feedback_include_details);
        includeDetails.setChecked(true);
        form.addView(includeDetails);
        addText(form, R.string.feedback_details_summary);

        preview = new TextView(context);
        preview.setTextIsSelectable(true);
        content.addView(preview);
        status = new TextView(context);
        status.setTextIsSelectable(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        email = new Button(context);
        email.setAllCaps(false);
        email.setOnClickListener(view -> openEmail());
        content.addView(email);

        fallback = new LinearLayout(context);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setVisibility(View.GONE);
        fallback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView noEmail = new TextView(context);
        noEmail.setText(context.getString(R.string.feedback_no_email, FeedbackEmail.ADDRESS));
        noEmail.setTextIsSelectable(true);
        fallback.addView(noEmail);
        Button copy = new Button(context);
        copy.setText(R.string.feedback_copy_address);
        copy.setAllCaps(false);
        copy.setOnClickListener(view -> {
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(
                    ClipData.newPlainText(context.getString(R.string.feedback_email_address), FeedbackEmail.ADDRESS));
            Toast.makeText(context, R.string.feedback_address_copied, Toast.LENGTH_SHORT).show();
        });
        fallback.addView(copy);
        content.addView(fallback);

        scroll = new ScrollView(context);
        scroll.addView(content);
        dialog = builder.setTitle(R.string.feedback_title).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.feedback_send_direct, null).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.setOnShowListener(opened -> bindActions());
    }

    private void bindActions() {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (submission.isAccepted()) {
                dialog.dismiss();
            } else if (submission.preview() != null) {
                submit(submission.preview());
            } else {
                showReview(submission.review(currentDraft()));
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
            if (submission.preview() == null) {
                dialog.dismiss();
            } else {
                submission.edit();
                resetDraftStatus();
                scroll.scrollTo(0, 0);
            }
        });
        categories.setOnCheckedChangeListener((group, checkedId) -> resetDraftStatus());
        includeDetails.setOnCheckedChangeListener((button, checked) -> resetDraftStatus());
        message.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                resetDraftStatus();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        resetDraftStatus();
    }

    private FeedbackSubmission.Draft currentDraft() {
        RadioButton selected = categories.findViewById(categories.getCheckedRadioButtonId());
        return new FeedbackSubmission.Draft((FeedbackEmail.Kind) selected.getTag(), message.getText().toString(),
                includeDetails.isChecked() ? details.get() : null);
    }

    private void showReview(FeedbackSubmission.Attempt attempt) {
        FeedbackSubmission.Draft draft = attempt.draft();
        FeedbackEmail.Details included = draft.details();
        String detailText = included == null ? activity.getString(R.string.feedback_review_no_details)
                : activity.getString(R.string.feedback_review_details, included.versionName(),
                included.versionCode(), included.androidVersion(), included.manufacturer(), included.model(),
                activity.getString(included.meshtasticConnected() ? R.string.feedback_yes : R.string.feedback_no),
                activity.getString(included.meshCoreConnected() ? R.string.feedback_yes : R.string.feedback_no));
        RadioButton selected = categories.findViewById(categories.getCheckedRadioButtonId());
        preview.setText(activity.getString(R.string.feedback_review_body, selected.getText(), attempt.id(),
                draft.message(), detailText));
        resetDraftStatus();
        hideKeyboard();
        scroll.scrollTo(0, 0);
    }

    private void submit(FeedbackSubmission.Attempt attempt) {
        if (!submission.start(attempt)) return;
        emailWarning = false;
        fallback.setVisibility(View.GONE);
        showStatus(R.string.feedback_sending);
        updateControls();
        showBottom();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            FeedbackClient.Outcome outcome = FeedbackClient.submit(attempt);
            main.post(() -> complete(attempt, outcome));
        }, "MeshParty feedback").start();
    }

    private void complete(FeedbackSubmission.Attempt attempt, FeedbackClient.Outcome outcome) {
        boolean accepted = outcome == FeedbackClient.Outcome.ACCEPTED;
        submission.finish(attempt, accepted, outcome == FeedbackClient.Outcome.UNCONFIRMED);
        if (!dialog.isShowing()) return;
        switch (outcome) {
            case ACCEPTED -> showStatus(R.string.feedback_receipt, attempt.id());
            case UNAVAILABLE -> showStatus(R.string.feedback_direct_unavailable);
            case BUSY -> showStatus(R.string.feedback_busy);
            case OFFLINE -> showStatus(R.string.feedback_offline);
            case REJECTED -> showStatus(R.string.feedback_rejected);
            case UNCONFIRMED -> showStatus(R.string.feedback_unconfirmed);
        }
        updateControls();
        showBottom();
    }

    private void openEmail() {
        boolean reviewing = submission.preview() != null;
        FeedbackSubmission.Draft draft = reviewing ? submission.preview().draft() : currentDraft();
        if (submission.needsEmailWarning(draft) && !emailWarning) {
            emailWarning = true;
            showStatus(R.string.feedback_email_duplicate_warning);
            updateControls();
            showBottom();
            return;
        }
        try {
            String mailto = FeedbackEmail.mailto(draft.kind(), draft.message(), draft.details());
            activity.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse(mailto)));
            dialog.dismiss();
        } catch (ActivityNotFoundException unavailable) {
            fallback.setVisibility(View.VISIBLE);
            hideKeyboard();
            showBottom();
        }
    }

    private void resetDraftStatus() {
        feedbackStatusForDraft();
        fallback.setVisibility(View.GONE);
        emailWarning = false;
        updateControls();
    }

    private void feedbackStatusForDraft() {
        if (directAvailable) {
            status.setVisibility(View.GONE);
        } else {
            showStatus(R.string.feedback_direct_unavailable);
        }
    }

    private void updateControls() {
        boolean accepting = submission.isAccepted();
        boolean reviewing = submission.preview() != null && !accepting;
        boolean sending = submission.isInFlight();
        boolean valid = categories.getCheckedRadioButtonId() != View.NO_ID
                && FeedbackSubmission.isValidMessage(message.getText().toString());
        form.setVisibility(reviewing || accepting ? View.GONE : View.VISIBLE);
        preview.setVisibility(reviewing ? View.VISIBLE : View.GONE);
        email.setVisibility(accepting ? View.GONE : View.VISIBLE);
        email.setText(emailWarning ? R.string.feedback_open_email : R.string.feedback_email_instead);
        email.setEnabled(!sending && (reviewing || valid));
        dialog.setCancelable(!sending);
        dialog.setTitle(reviewing ? R.string.feedback_review_title : R.string.feedback_title);

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setText(accepting ? R.string.feedback_done : reviewing ? R.string.feedback_confirm
                : R.string.feedback_send_direct);
        positive.setEnabled(!sending && (accepting || (directAvailable && (reviewing || valid))));
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        negative.setVisibility(accepting ? View.GONE : View.VISIBLE);
        negative.setEnabled(!sending);
        negative.setText(reviewing ? R.string.feedback_edit : android.R.string.cancel);
    }

    private void showStatus(int resource, Object... args) {
        status.setText(activity.getString(resource, args));
        status.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        WindowInsetsController insets = scroll.getWindowInsetsController();
        if (insets != null) insets.hide(WindowInsets.Type.ime());
    }

    private void showBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private static void addKind(RadioGroup group, FeedbackEmail.Kind kind, int label) {
        RadioButton option = new RadioButton(group.getContext());
        option.setId(View.generateViewId());
        option.setText(label);
        option.setTag(kind);
        group.addView(option);
    }

    private static void addText(LinearLayout form, int text) {
        TextView view = new TextView(form.getContext());
        view.setText(text);
        form.addView(view);
    }
}
