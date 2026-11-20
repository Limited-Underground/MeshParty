package com.limitedunderground.meshinbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
    private FeedbackDialog() {}

    static AlertDialog create(Activity activity, Supplier<FeedbackEmail.Details> details) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        Context context = builder.getContext();
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * context.getResources().getDisplayMetrics().density);
        form.setPadding(inset, 0, inset, inset / 2);
        form.setSaveFromParentEnabled(false);

        addText(form, R.string.feedback_intro);
        RadioGroup categories = new RadioGroup(context);
        addKind(categories, FeedbackEmail.Kind.LIKE, R.string.feedback_like);
        addKind(categories, FeedbackEmail.Kind.DISLIKE, R.string.feedback_dislike);
        addKind(categories, FeedbackEmail.Kind.CHANGE, R.string.feedback_change);
        addKind(categories, FeedbackEmail.Kind.IDEA, R.string.feedback_idea);
        addKind(categories, FeedbackEmail.Kind.BUG, R.string.feedback_bug);
        form.addView(categories);

        EditText message = new EditText(context);
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

        Switch includeDetails = new Switch(context);
        includeDetails.setText(R.string.feedback_include_details);
        includeDetails.setChecked(true);
        form.addView(includeDetails);
        addText(form, R.string.feedback_details_summary);

        LinearLayout fallback = new LinearLayout(context);
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
        form.addView(fallback);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        AlertDialog dialog = builder.setTitle(R.string.feedback_title).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.feedback_send, null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.setOnShowListener(opened -> {
            Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable updateSend = () -> send.setEnabled(categories.getCheckedRadioButtonId() != View.NO_ID
                    && FeedbackEmail.isValidMessage(message.getText().toString()));
            updateSend.run();
            categories.setOnCheckedChangeListener((group, checkedId) -> updateSend.run());
            message.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    updateSend.run();
                }
                @Override public void afterTextChanged(Editable text) {}
            });
            send.setOnClickListener(view -> {
                RadioButton selected = categories.findViewById(categories.getCheckedRadioButtonId());
                FeedbackEmail.Kind kind = (FeedbackEmail.Kind) selected.getTag();
                String mailto = FeedbackEmail.mailto(kind, message.getText().toString(),
                        includeDetails.isChecked() ? details.get() : null);
                try {
                    activity.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse(mailto)));
                    dialog.dismiss();
                } catch (ActivityNotFoundException unavailable) {
                    fallback.setVisibility(View.VISIBLE);
                    WindowInsetsController insets = scroll.getWindowInsetsController();
                    if (insets != null) insets.hide(WindowInsets.Type.ime());
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                }
            });
        });
        return dialog;
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
