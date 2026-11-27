package com.limitedunderground.meshinbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the app languages consistent (D-197): every Text.t English matches strings.xml, the
 * language list agrees with locales_config.xml and the res/values-* folders, every translation
 * has the same names and placeholders as English, and every non-English file says whether it
 * was reviewed. Run from the repository root (or pass it as the first argument).
 */
public final class LanguageResourcesTest {
    private static final Pattern STRING = Pattern.compile(
            "<string name=\"([^\"]+)\"([^>]*)>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern TEXT_CALL = Pattern.compile("Text\\.t\\(\"([a-z0-9_]+)\", \"((?:[^\"\\\\]|\\\\.)*)\"");
    /** Regions and MeshCore presets carry their key and English in the constructor; label() calls Text.t(key, english). */
    private static final Pattern KEYED_CONSTRUCTOR = Pattern.compile(
            "new (?:Region|Preset)\\((?:\\d+, )?\"(text_[a-z0-9_]+)\", \"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PLACEHOLDER =Pattern.compile("%\\d+\\$,?[sd]");
    private static final Pattern LANGUAGE = Pattern.compile("^\\s+[A-Z_]+\\(\"([^\"]*)\", (?:null|\"[^\"]+\")\\)[,;]", Pattern.MULTILINE);
    private static final Pattern LOCALE = Pattern.compile("<locale android:name=\"([^\"]+)\"");

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path main = root.resolve("app/src/main");
        Path res = main.resolve("res");
        Map<String, String> english = strings(res.resolve("values/strings.xml"), false);

        textCallsMatchEnglish(main.resolve("java"), english);
        List<String> tags = languageTags(main.resolve("java/com/limitedunderground/meshinbox/AppLanguage.java"));
        String config = Files.readString(res.resolve("xml/locales_config.xml"), StandardCharsets.UTF_8);
        List<String> configured = new ArrayList<>();
        for (Matcher m = LOCALE.matcher(config); m.find(); ) configured.add(m.group(1));
        check(configured.equals(tags), "locales_config.xml " + configured + " matches AppLanguage " + tags);

        Set<String> expected = new TreeSet<>();
        for (String tag : tags) if (!tag.equals("en")) expected.add(folder(tag));
        Set<String> found = new TreeSet<>();
        try (Stream<Path> folders = Files.list(res)) {
            folders.filter(p -> p.getFileName().toString().startsWith("values-"))
                    .filter(p -> Files.exists(p.resolve("strings.xml")))
                    .forEach(p -> found.add(p.getFileName().toString()));
        }
        check(found.equals(expected), "values folders " + found + " match AppLanguage " + expected);

        for (String name : found) {
            Path file = res.resolve(name).resolve("strings.xml");
            String text = Files.readString(file, StandardCharsets.UTF_8);
            check(text.startsWith("<!-- UNREVIEWED:") || text.startsWith("<!-- REVIEWED:"),
                    name + " starts with its review state");
            Map<String, String> translated = strings(file, true);
            check(translated.keySet().equals(english.keySet()), name + " has exactly the English names; missing "
                    + difference(english.keySet(), translated.keySet()) + " extra "
                    + difference(translated.keySet(), english.keySet()));
            for (Map.Entry<String, String> entry : english.entrySet()) {
                check(placeholders(entry.getValue()).equals(placeholders(translated.get(entry.getKey()))),
                        name + " " + entry.getKey() + " keeps the placeholders");
            }
            check(text.contains("<plurals name=\"feedback_message\">"), name + " has the feedback_message plural");
        }
        System.out.println("Language resource host checks passed (" + found.size() + " translations, "
                + english.size() + " strings)");
    }

    private static void textCallsMatchEnglish(Path java, Map<String, String> english) throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(java)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        int calls = 0;
        for (Path source : sources) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            int indirect = code.split("Text\\.t\\(key, english\\)", -1).length - 1;
            int all = code.split("Text\\.t\\(", -1).length - 1 - indirect;
            int matched = 0;
            for (Matcher m = KEYED_CONSTRUCTOR.matcher(code); m.find(); calls++) {
                check(m.group(2).equals(english.get(m.group(1))),
                        source.getFileName() + ": " + m.group(1) + " constructor English matches strings.xml");
            }
            for (Matcher m = TEXT_CALL.matcher(code); m.find(); matched++) {
                String key = m.group(1);
                String fallback = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
                check(english.containsKey(key), source.getFileName() + ": " + key + " is in strings.xml");
                check(english.get(key).equals(fallback), source.getFileName() + ": " + key + " English matches strings.xml: '"
                        + fallback + "' vs '" + english.get(key) + "'");
            }
            check(all == matched, source.getFileName() + ": every Text.t call names its key and English in one literal");
            calls += matched;
        }
        check(calls > 100, "Text.t calls found: " + calls);
    }

    private static List<String> languageTags(Path appLanguage) throws IOException {
        String code = Files.readString(appLanguage, StandardCharsets.UTF_8);
        List<String> tags = new ArrayList<>();
        for (Matcher m = LANGUAGE.matcher(code); m.find(); ) if (!m.group(1).isEmpty()) tags.add(m.group(1));
        check(tags.size() == 10, "AppLanguage lists English and nine languages: " + tags);
        return tags;
    }

    private static String folder(String tag) {
        String[] parts = tag.split("-");
        return parts.length == 1 ? "values-" + tag : "values-b+" + parts[0] + "+" + parts[1];
    }

    private static Map<String, String> strings(Path file, boolean translatedOnly) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> result = new TreeMap<>();
        for (Matcher m = STRING.matcher(text); m.find(); ) {
            if (m.group(2).contains("translatable=\"false\"")) continue;
            result.put(m.group(1), unescape(m.group(3)));
        }
        return result;
    }

    private static String unescape(String value) {
        String text = value;
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) text = text.substring(1, text.length() - 1);
        return text.replace("\\'", "'").replace("\\\"", "\"").replace("\\@", "@").replace("\\?", "?")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        for (Matcher m = PLACEHOLDER.matcher(text); m.find(); ) found.add(m.group());
        found.sort(null);
        return found;
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static void check(boolean outcome, String label) {
        if (!outcome) throw new AssertionError(label);
    }
}
