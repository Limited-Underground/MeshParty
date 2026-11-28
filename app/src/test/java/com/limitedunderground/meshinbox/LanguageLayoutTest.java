package com.limitedunderground.meshinbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.os.Looper;
import android.text.Layout;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders MeshParty's screens and setup forms in every language on a 360 dp phone and fails if
 * text is cut off, runs off the screen, or a tab or filter label wraps (D-197).
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h640dp-port-xxhdpi")
// Real text measurement; the legacy graphics mode does not measure text width.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LanguageLayoutTest {
    private final String qualifier;
    private MainActivity activity;
    private int screenWidth;
    private int screenHeight;

    public LanguageLayoutTest(String qualifier) {
        this.qualifier = qualifier;
    }

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static List<Object[]> languages() {
        List<Object[]> values = new ArrayList<>();
        for (String tag : List.of("en", "es", "it", "fr", "de", "hr", "b+sr+Latn", "b+sr+Cyrl", "ru", "uk")) {
            values.add(new Object[]{tag});
        }
        return values;
    }

    @Before
    public void startInLanguage() {
        RuntimeEnvironment.setQualifiers("+" + qualifier);
        // The screens render as they do before the radio service connects.
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName(RuntimeEnvironment.getApplication(), MeshService.class));
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    @Test
    public void mainScreensFit() {
        Set<String> oneLine = Set.of(activity.getString(R.string.tab_messages), activity.getString(R.string.tab_radios),
                activity.getString(R.string.tab_settings), activity.getString(R.string.filter_all));
        View root = activity.getWindow().getDecorView();
        checkScreen(root, oneLine, "messages");
        open(root, R.string.tab_radios);
        checkScreen(root, oneLine, "radios");
        open(root, R.string.tab_settings);
        checkScreen(root, oneLine, "settings");
    }

    @Test
    public void setupFormsFit() {
        MeshtasticSetup.Current meshtastic = new MeshtasticSetup.Current(0, true, 0, "Meshtastic 1a2b", "1a2b",
                true, Map.of());
        checkDialog(RadioSetupDialog.create(activity, meshtastic, request -> null), "Meshtastic setup");
        MeshCoreSetup.Current meshcore = new MeshCoreSetup.Current(869_618, 62_500, 8, 8, "a1b2c3d4", Boolean.FALSE);
        checkDialog(MeshCoreSetupDialog.create(activity, meshcore, request -> null), "MeshCore setup");
    }

    @Test
    public void startupWindowsFit() {
        // A fresh install opens on the chat rules window (D-205).
        AlertDialog rules = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(qualifier + ": chat rules window at start", rules);
        assertTrue(qualifier + ": chat rules window showing", rules.isShowing());
        checkDialog(rules, "chat rules");
        // What's new from Settings › About lists the 0.2.x releases (D-207).
        ChatModeration.acceptRules(activity.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE));
        View root = activity.getWindow().getDecorView();
        open(root, R.string.tab_settings);
        open(root, R.string.whats_new_button);
        AlertDialog news = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(qualifier + ": What's new opens", news != rules && news.isShowing());
        checkDialog(news, "what's new");
    }

    @Test
    public void languageChoiceShowsEachLanguageOnOneLine() {
        int width = dialogWidth();
        for (AppLanguage language : AppLanguage.values()) {
            TextView row = (TextView) LayoutInflater.from(activity)
                    .inflate(android.R.layout.simple_list_item_single_choice, null, false);
            row.setText(language.label(activity));
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
            assertFits(row, width, true, "language list");
        }
    }

    private void open(View root, int tab) {
        String title = activity.getString(tab);
        View button = find(root, title);
        assertNotNull(qualifier + ": tab " + title, button);
        button.performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void checkScreen(View root, Set<String> oneLine, String screen) {
        shadowOf(Looper.getMainLooper()).idle();
        root.measure(View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, screenWidth, screenHeight);
        walk(root, oneLine, screen);
    }

    private void checkDialog(AlertDialog dialog, String name) {
        dialog.show();
        shadowOf(Looper.getMainLooper()).idle();
        View root = dialog.getWindow().getDecorView();
        int width = dialogWidth();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.layout(0, 0, width, root.getMeasuredHeight());
        walkWithin(root, width, name);
        dialog.dismiss();
    }

    /** A phone dialog is narrower than the screen; 24 dp each side is a conservative width. */
    private int dialogWidth() {
        return screenWidth - Math.round(48 * activity.getResources().getDisplayMetrics().density);
    }

    private void walk(View view, Set<String> oneLine, String where) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView text && text.getText().length() > 0 && text.getWidth() > 0) {
            assertFits(text, screenWidth, oneLine.contains(text.getText().toString()), where);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), oneLine, where);
        }
    }

    private void walkWithin(View view, int width, String where) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView text && text.getText().length() > 0 && text.getWidth() > 0) {
            assertFits(text, width, false, where);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) walkWithin(group.getChildAt(i), width, where);
        }
    }

    private void assertFits(TextView view, int width, boolean singleLine, String where) {
        String text = view.getText().toString();
        String label = qualifier + " " + where + ": '" + text + "'";
        Layout layout = view.getLayout();
        assertNotNull(label + " has no layout", layout);
        int lines = layout.getLineCount();
        float widest = 0;
        for (int i = 0; i < lines; i++) {
            assertEquals(label + " is ellipsized", 0, layout.getEllipsisCount(i));
            widest = Math.max(widest, layout.getLineWidth(i));
        }
        // Wrapped text was broken to fit by Android itself; a single line can still overflow a fixed box.
        // Native glyph measurement runs about 1% over the layout's own width, hence the small allowance.
        int available = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
        if (lines == 1) {
            assertTrue(label + " is wider than its box (" + widest + " > " + available + ")",
                    widest <= available + Math.max(2, available * 0.02f));
        }
        assertTrue(label + " has more lines than it may show", lines <= view.getMaxLines());
        int needed = layout.getHeight() + view.getCompoundPaddingTop() + view.getCompoundPaddingBottom();
        assertTrue(label + " is cut off at the bottom (" + needed + " > " + view.getHeight() + ")",
                needed <= view.getHeight() + 1);
        int[] location = new int[2];
        view.getLocationInWindow(location);
        assertTrue(label + " runs off the screen", location[0] >= 0 && location[0] + view.getWidth() <= width + 1);
        if (singleLine) assertEquals(label + " wraps", 1, lines);
    }

    private static View find(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
