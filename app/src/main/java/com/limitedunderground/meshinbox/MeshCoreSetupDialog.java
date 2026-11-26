package com.limitedunderground.meshinbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * First-time MeshCore setup (D-184): a regional preset from a short list or "keep current",
 * the node name, and an optional advert. No preset is pre-selected; the user must choose.
 */
final class MeshCoreSetupDialog {
    private final Activity activity;
    private final MeshCoreSetup.Current current;
    private final Function<MeshCoreSetup.Request, String> apply;
    private final AlertDialog dialog;
    private final Button presetButton;
    private final Button advertButton;
    private final EditText name;
    private final TextView status;
    private boolean presetChosen;
    /** Null keeps the radio settings as they are. */
    private MeshCoreSetup.Preset preset;
    private MeshCoreSetup.Advert advert = MeshCoreSetup.Advert.NEARBY;

    static AlertDialog create(Activity activity, MeshCoreSetup.Current current,
                              Function<MeshCoreSetup.Request, String> apply) {
        return new MeshCoreSetupDialog(activity, current, apply).dialog;
    }

    private MeshCoreSetupDialog(Activity activity, MeshCoreSetup.Current current,
                                Function<MeshCoreSetup.Request, String> apply) {
        this.activity = activity;
        this.current = current;
        this.apply = apply;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        Context context = builder.getContext();
        ScrollView scroll = new ScrollView(context);
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * context.getResources().getDisplayMetrics().density);
        form.setPadding(inset, 0, inset, inset / 2);
        scroll.addView(form);

        addText(form, context.getString(R.string.meshcore_setup_intro));
        addText(form, context.getString(R.string.meshcore_setup_now, MeshCoreSetup.describe(current)));
        addText(form, context.getString(R.string.meshcore_setup_band_hint));

        presetButton = new Button(context);
        presetButton.setAllCaps(false);
        presetButton.setOnClickListener(view -> choosePreset());
        form.addView(presetButton);

        name = new EditText(context);
        name.setHint(R.string.meshcore_setup_name);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setSingleLine(true);
        name.setText(current.name());
        form.addView(name);

        advertButton = new Button(context);
        advertButton.setAllCaps(false);
        advertButton.setOnClickListener(view -> chooseAdvert());
        form.addView(advertButton);

        addText(form, context.getString(R.string.meshcore_setup_rules));
        status = new TextView(context);
        status.setTextSize(14);
        status.setTypeface(status.getTypeface(), android.graphics.Typeface.BOLD);
        form.addView(status);

        dialog = builder.setTitle(R.string.meshcore_setup_title).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setup_save, null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> save()));
        refresh();
    }

    private void choosePreset() {
        List<MeshCoreSetup.Preset> presets = MeshCoreSetup.PRESETS;
        List<String> titles = new ArrayList<>();
        titles.add(activity.getString(R.string.meshcore_setup_keep));
        int selected = presetChosen && preset == null ? 0 : -1;
        for (MeshCoreSetup.Preset option : presets) {
            if (presetChosen && option.equals(preset)) selected = titles.size();
            titles.add(option.label() + " · " + option.values());
        }
        new AlertDialog.Builder(activity).setTitle(R.string.meshcore_setup_preset_title)
                .setSingleChoiceItems(titles.toArray(new String[0]), selected, (picker, which) -> {
                    presetChosen = true;
                    preset = which == 0 ? null : presets.get(which - 1);
                    picker.dismiss();
                    refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseAdvert() {
        MeshCoreSetup.Advert[] values = MeshCoreSetup.Advert.values();
        String[] titles = new String[values.length];
        for (int i = 0; i < values.length; i++) titles[i] = advertLabel(values[i]);
        new AlertDialog.Builder(activity).setTitle(R.string.meshcore_setup_advert_title)
                .setSingleChoiceItems(titles, advert.ordinal(), (picker, which) -> {
                    advert = values[which];
                    picker.dismiss();
                    refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private String advertLabel(MeshCoreSetup.Advert value) {
        return activity.getString(switch (value) {
            case NONE -> R.string.meshcore_setup_advert_none;
            case NEARBY -> R.string.meshcore_setup_advert_nearby;
            case MESH -> R.string.meshcore_setup_advert_mesh;
        });
    }

    private void refresh() {
        String presetText = !presetChosen ? activity.getString(R.string.setup_region_choose)
                : preset == null ? activity.getString(R.string.meshcore_setup_keep) : preset.label();
        presetButton.setText(activity.getString(R.string.meshcore_setup_preset_value, presetText));
        advertButton.setText(activity.getString(R.string.meshcore_setup_advert_value, advertLabel(advert)));
        if (presetChosen) status.setText("");
    }

    private void save() {
        if (!presetChosen) {
            status.setText(R.string.meshcore_setup_choose_preset);
            return;
        }
        MeshCoreSetup.Request request = new MeshCoreSetup.Request(preset, name.getText().toString(), advert);
        String problem = MeshCoreSetup.validate(request, current);
        if (problem != null) {
            status.setText(problem);
            return;
        }
        String warning = MeshCoreSetup.bandChangeWarning(current, preset);
        if (warning == null) {
            send(request);
            return;
        }
        new AlertDialog.Builder(activity).setTitle(R.string.setup_band_title).setMessage(warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setup_band_confirm, (confirm, which) -> send(request))
                .show();
    }

    private void send(MeshCoreSetup.Request request) {
        String problem = apply.apply(request);
        if (problem != null) {
            status.setText(problem);
            return;
        }
        dialog.dismiss();
    }

    private static void addText(LinearLayout form, String text) {
        TextView view = new TextView(form.getContext());
        view.setText(text);
        view.setTextSize(14);
        int gap = Math.round(8 * form.getContext().getResources().getDisplayMetrics().density);
        view.setPadding(0, gap, 0, gap / 2);
        form.addView(view);
    }
}
