package com.limitedunderground.meshinbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
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
 * First-time Meshtastic setup (D-184): region, modem preset and names. The region is never
 * pre-selected; the user must choose it.
 */
final class RadioSetupDialog {
    private final Activity activity;
    private final MeshtasticSetup.Current current;
    private final Function<MeshtasticSetup.Request, String> apply;
    private final AlertDialog dialog;
    private final Button regionButton;
    private final Button presetButton;
    private final EditText longName;
    private final EditText shortName;
    private final TextView status;
    private int region;
    /** Null keeps the radio's current modem settings. */
    private Integer preset;

    static AlertDialog create(Activity activity, MeshtasticSetup.Current current,
                              Function<MeshtasticSetup.Request, String> apply) {
        return new RadioSetupDialog(activity, current, apply).dialog;
    }

    private RadioSetupDialog(Activity activity, MeshtasticSetup.Current current,
                             Function<MeshtasticSetup.Request, String> apply) {
        this.activity = activity;
        this.current = current;
        this.apply = apply;
        // Keep the region the radio already has, but never fill in one for an unset radio.
        region = current.regionSet() && MeshtasticSetup.region(current.region()) != null
                ? current.region() : MeshtasticSetup.REGION_UNSET;
        preset = current.usePreset() ? current.preset() : null;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        Context context = builder.getContext();
        ScrollView scroll = new ScrollView(context);
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * context.getResources().getDisplayMetrics().density);
        form.setPadding(inset, 0, inset, inset / 2);
        scroll.addView(form);

        addText(form, context.getString(R.string.setup_intro));
        String now = context.getString(R.string.setup_now, MeshtasticSetup.regionLabel(current.region()),
                current.usePreset() ? MeshtasticSetup.presetLabel(current.preset())
                        : context.getString(R.string.setup_custom_modem));
        addText(form, now);
        if (current.regionSet() && !current.txEnabled()) addText(form, context.getString(R.string.setup_tx_off));

        regionButton = new Button(context);
        regionButton.setAllCaps(false);
        regionButton.setOnClickListener(view -> chooseRegion());
        form.addView(regionButton);

        presetButton = new Button(context);
        presetButton.setAllCaps(false);
        presetButton.setOnClickListener(view -> choosePreset());
        form.addView(presetButton);

        longName = new EditText(context);
        longName.setHint(R.string.setup_long_name);
        longName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        longName.setSingleLine(true);
        longName.setText(current.longName());
        form.addView(longName);

        shortName = new EditText(context);
        shortName.setHint(R.string.setup_short_name);
        shortName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        shortName.setSingleLine(true);
        shortName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        shortName.setText(current.shortName());
        form.addView(shortName);

        addText(form, context.getString(R.string.setup_rules));
        status = new TextView(context);
        status.setTextSize(14);
        form.addView(status);

        dialog = builder.setTitle(R.string.setup_title).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setup_save, null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> save()));
        refreshChoices();
    }

    private void chooseRegion() {
        List<MeshtasticSetup.Region> regions = MeshtasticSetup.REGIONS;
        String[] titles = new String[regions.size()];
        int selected = -1;
        for (int i = 0; i < regions.size(); i++) {
            titles[i] = regions.get(i).title();
            if (regions.get(i).code() == region) selected = i;
        }
        new AlertDialog.Builder(activity).setTitle(R.string.setup_region_title)
                .setSingleChoiceItems(titles, selected, (picker, which) -> {
                    region = regions.get(which).code();
                    // Keep the current preset only where the new region allows it.
                    if (preset == null || !allowed(preset)) {
                        preset = current.usePreset() && allowed(current.preset()) ? current.preset()
                                : MeshtasticSetup.defaultPreset(region, current.regionRules());
                        if (!allowed(preset)) preset = null;
                    }
                    picker.dismiss();
                    refreshChoices();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void choosePreset() {
        List<MeshtasticSetup.Preset> presets = offeredPresets();
        List<String> titles = new ArrayList<>();
        int selected = -1;
        boolean keep = !current.usePreset();
        if (keep) {
            titles.add(activity.getString(R.string.setup_keep_custom));
            if (preset == null) selected = 0;
        }
        for (MeshtasticSetup.Preset option : presets) {
            if (preset != null && option.code() == preset) selected = titles.size();
            titles.add(option.label());
        }
        int offset = keep ? 1 : 0;
        new AlertDialog.Builder(activity).setTitle(R.string.setup_preset_title)
                .setSingleChoiceItems(titles.toArray(new String[0]), selected, (picker, which) -> {
                    preset = which < offset ? null : presets.get(which - offset).code();
                    picker.dismiss();
                    refreshChoices();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private List<MeshtasticSetup.Preset> offeredPresets() {
        int forRegion = region == MeshtasticSetup.REGION_UNSET ? -1 : region;
        return MeshtasticSetup.presetsFor(forRegion, current.regionRules());
    }

    private boolean allowed(int code) {
        for (MeshtasticSetup.Preset option : offeredPresets()) if (option.code() == code) return true;
        return false;
    }

    private void refreshChoices() {
        String regionText = region == MeshtasticSetup.REGION_UNSET
                ? activity.getString(R.string.setup_region_choose)
                : MeshtasticSetup.region(region).title();
        regionButton.setText(activity.getString(R.string.setup_region_value, regionText));
        String presetText = preset == null ? activity.getString(R.string.setup_custom_modem)
                : MeshtasticSetup.presetLabel(preset);
        presetButton.setText(activity.getString(R.string.setup_preset_value, presetText));
        presetButton.setEnabled(region != MeshtasticSetup.REGION_UNSET);
    }

    private void save() {
        MeshtasticSetup.Request request = new MeshtasticSetup.Request(region, preset,
                longName.getText().toString(), shortName.getText().toString());
        String problem = MeshtasticSetup.validate(request, current.regionRules());
        if (problem == null) problem = apply.apply(request);
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
