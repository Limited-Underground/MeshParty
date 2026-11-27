package com.limitedunderground.meshinbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * The app languages (D-197). Keep this list, res/xml/locales_config.xml and the res/values-*
 * folders in step; LanguageResourcesTest fails if they drift. Android 13 and later store the
 * choice per app; Android 12 stores it here and applies it when a screen or the service starts.
 */
enum AppLanguage {
    SYSTEM("", null),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    ITALIAN("it", "Italiano"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    CROATIAN("hr", "Hrvatski"),
    SERBIAN_LATIN("sr-Latn", "Srpski (latinica)"),
    SERBIAN_CYRILLIC("sr-Cyrl", "Српски (ћирилица)"),
    RUSSIAN("ru", "Русский"),
    UKRAINIAN("uk", "Українська");

    private static final String PREFERENCES = "settings";
    private static final String KEY = "language";

    /** BCP 47 tag, empty for the system default. */
    final String tag;
    /** The language's name in itself; null for the system default, which is translated. */
    final String nativeName;

    AppLanguage(String tag, String nativeName) {
        this.tag = tag;
        this.nativeName = nativeName;
    }

    String label(Context context) {
        return nativeName != null ? nativeName : context.getString(R.string.language_system_default);
    }

    static AppLanguage fromTag(String tag) {
        if (tag == null || tag.isEmpty()) return SYSTEM;
        for (AppLanguage language : values()) {
            if (language.tag.equalsIgnoreCase(tag)) return language;
        }
        String base = Locale.forLanguageTag(tag).getLanguage();
        for (AppLanguage language : values()) {
            if (!language.tag.isEmpty() && language.tag.equalsIgnoreCase(base)) return language;
        }
        return SYSTEM;
    }

    static AppLanguage current(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            LocaleList chosen = manager == null ? LocaleList.getEmptyLocaleList() : manager.getApplicationLocales();
            return chosen.isEmpty() ? SYSTEM : fromTag(chosen.get(0).toLanguageTag());
        }
        return fromTag(preferences(context).getString(KEY, ""));
    }

    /** Applies a choice. Android 13+ restarts the screens itself; on Android 12 the caller recreates them. */
    static void choose(Activity activity, AppLanguage language) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = activity.getSystemService(LocaleManager.class);
            if (manager != null) {
                manager.setApplicationLocales(language == SYSTEM ? LocaleList.getEmptyLocaleList()
                        : LocaleList.forLanguageTags(language.tag));
            }
            return;
        }
        preferences(activity).edit().putString(KEY, language.tag).apply();
        localizedResources = null;
        activity.recreate();
    }

    /** On Android 12, a context in the stored language; otherwise the context itself. */
    // Bundles keep every language (bundle.language.enableSplit = false in app/build.gradle).
    @SuppressLint("AppBundleLocaleChanges")
    static Context wrap(Context base) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base;
        AppLanguage language = fromTag(preferences(base).getString(KEY, ""));
        if (language == SYSTEM) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(LocaleList.forLanguageTags(language.tag));
        return base.createConfigurationContext(configuration);
    }

    private static volatile Resources localizedResources;

    /** Resources in the app's language, for text built outside a screen or the service. */
    static Resources resources(Context application) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return application.getResources();
        Resources cached = localizedResources;
        if (cached == null) {
            cached = wrap(application).getResources();
            localizedResources = cached;
        }
        return cached;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
