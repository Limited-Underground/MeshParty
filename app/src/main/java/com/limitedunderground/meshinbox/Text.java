package com.limitedunderground.meshinbox;

import java.util.Locale;

/**
 * User-facing text for classes without an Android Context (the radio protocols and setup
 * models), D-197. Each call names a string resource and repeats its English text, so host
 * tests run in English with no Android code. The app points this at the string resources in
 * MeshPartyApplication. LanguageResourcesTest keeps the English here identical to
 * res/values/strings.xml.
 */
final class Text {
    /** Returns the text for a resource name in the app's language, or null when there is none. */
    interface Source {
        String find(String key);
    }

    private static volatile Source source = key -> null;

    private Text() {}

    static void use(Source next) {
        source = next == null ? key -> null : next;
    }

    /** The translated text for {@code key}, or {@code english}; formatted with {@code args} if any. */
    static String t(String key, String english, Object... args) {
        String template = null;
        try {
            template = source.find(key);
        } catch (RuntimeException ignored) {
            // A missing resource falls back to English.
        }
        if (template == null) template = english;
        return args.length == 0 ? template : String.format(Locale.getDefault(), template, args);
    }
}
