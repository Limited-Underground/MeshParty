package com.limitedunderground.meshinbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First-time Meshtastic radio setup: region, modem preset and owner names (D-184).
 * Values follow meshtastic/protobufs config.proto (LoRaConfig) and mesh.proto (User).
 */
final class MeshtasticSetup {
    static final int REGION_UNSET = 0;
    static final int LONG_FAST = 0;
    /** mesh.proto: "Clients should enforce 24 bytes in their UI." */
    static final int LONG_NAME_BYTES = 24;
    /** Up to four characters and four UTF-8 bytes, as the official clients allow. */
    static final int SHORT_NAME_CHARACTERS = 4;

    record Region(int code, String label, String band) {
        String title() { return label + " · " + band; }
    }

    record Preset(int code, String label) {}

    /** What the radio reported. Allowed presets are empty when the radio sent no region map. */
    record Current(int region, boolean usePreset, int preset, String longName, String shortName,
                   boolean txEnabled, Map<Integer, RegionRule> regionRules) {
        boolean regionSet() { return region != REGION_UNSET; }
    }

    /** One entry of the firmware's region preset map (mesh.proto LoRaRegionPresetMap). */
    record RegionRule(List<Integer> presets, int defaultPreset, boolean licensedOnly) {}

    /** What the user chose. A null preset keeps the radio's current modem settings. */
    record Request(int region, Integer preset, String longName, String shortName) {}

    /**
     * Regions a user can choose. Amateur (ITU) bands need a licence and are left out,
     * as is UA_868, deprecated since firmware 2.8.0. Bands are from meshtastic.org.
     */
    static final List<Region> REGIONS;
    static {
        List<Region> regions = new ArrayList<>(List.of(
                new Region(1, "United States", "902–928 MHz"),
                new Region(2, "European Union 433", "433–434 MHz"),
                new Region(3, "European Union 868", "869.4–869.65 MHz"),
                new Region(4, "China", "470–510 MHz"),
                new Region(5, "Japan", "920.5–923.5 MHz"),
                new Region(6, "Australia / Brazil / New Zealand", "915–928 MHz"),
                new Region(7, "Korea", "920–923 MHz"),
                new Region(8, "Taiwan", "920–925 MHz"),
                new Region(9, "Russia", "868.7–869.2 MHz"),
                new Region(10, "India", "865–867 MHz"),
                new Region(11, "New Zealand 865", "864–868 MHz"),
                new Region(12, "Thailand", "920–925 MHz"),
                new Region(13, "2.4 GHz (worldwide)", "2400–2483.5 MHz"),
                new Region(14, "Ukraine 433", "433–434.7 MHz"),
                new Region(16, "Malaysia 433", "433–435 MHz"),
                new Region(17, "Malaysia 919", "919–924 MHz"),
                new Region(18, "Singapore 923", "917–925 MHz"),
                new Region(19, "Philippines 433", "433–434.7 MHz"),
                new Region(20, "Philippines 868", "868–869.4 MHz"),
                new Region(21, "Philippines 915", "915–918 MHz"),
                new Region(22, "Australia / New Zealand 433", "433.05–434.79 MHz"),
                new Region(23, "Kazakhstan 433", "433.075–434.775 MHz"),
                new Region(24, "Kazakhstan 863", "863–868 MHz"),
                new Region(25, "Nepal 865", "865–868 MHz"),
                new Region(26, "Brazil 902", "902–907.5 MHz"),
                new Region(29, "European Union 866", "865.6–867.6 MHz"),
                new Region(30, "European Union 874", "874 MHz band"),
                new Region(31, "European Union 917", "917 MHz band"),
                new Region(32, "European Union 868 (Narrow)", "869.4–869.65 MHz")));
        regions.sort(Comparator.comparing(Region::label));
        REGIONS = List.copyOf(regions);
    }

    /** General presets, in the order the official docs list them. Narrowband presets are region-only. */
    static final List<Preset> PRESETS = List.of(
            new Preset(0, "LongFast (default)"),
            new Preset(7, "LongModerate"),
            new Preset(9, "LongTurbo"),
            new Preset(3, "MediumSlow"),
            new Preset(4, "MediumFast"),
            new Preset(16, "MediumTurbo"),
            new Preset(5, "ShortSlow"),
            new Preset(6, "ShortFast"),
            new Preset(8, "ShortTurbo"));

    private static final List<Preset> REGION_ONLY_PRESETS = List.of(
            new Preset(10, "LiteFast"), new Preset(11, "LiteSlow"),
            new Preset(12, "NarrowFast"), new Preset(13, "NarrowSlow"),
            new Preset(14, "TinyFast"), new Preset(15, "TinySlow"));

    private MeshtasticSetup() {}

    static Region region(int code) {
        for (Region region : REGIONS) if (region.code() == code) return region;
        return null;
    }

    static String regionLabel(int code) {
        if (code == REGION_UNSET) return "Not set";
        Region region = region(code);
        return region == null ? "Region " + code : region.label();
    }

    static String presetLabel(int code) {
        for (Preset preset : PRESETS) if (preset.code() == code) return preset.label().replace(" (default)", "");
        for (Preset preset : REGION_ONLY_PRESETS) if (preset.code() == code) return preset.label();
        return "Preset " + code;
    }

    /**
     * Presets offered for a region. With a firmware region map, only presets it allows
     * (and no licensed-only group); without one, the general list.
     */
    static List<Preset> presetsFor(int region, Map<Integer, RegionRule> rules) {
        RegionRule rule = rules == null ? null : rules.get(region);
        if (rule == null) return PRESETS;
        if (rule.licensedOnly()) return List.of();
        List<Preset> allowed = new ArrayList<>();
        for (Preset preset : PRESETS) if (rule.presets().contains(preset.code())) allowed.add(preset);
        for (Preset preset : REGION_ONLY_PRESETS) if (rule.presets().contains(preset.code())) allowed.add(preset);
        return allowed;
    }

    /** The preset to suggest for a region: the firmware default when known, LongFast otherwise. */
    static int defaultPreset(int region, Map<Integer, RegionRule> rules) {
        RegionRule rule = rules == null ? null : rules.get(region);
        return rule == null ? LONG_FAST : rule.defaultPreset();
    }

    /** Returns a user-facing problem with the request, or null when it can be sent. */
    static String validate(Request request, Map<Integer, RegionRule> rules) {
        if (request.region() == REGION_UNSET || region(request.region()) == null) return "Choose your region.";
        RegionRule rule = rules == null ? null : rules.get(request.region());
        if (rule != null && rule.licensedOnly()) return "This region needs an amateur radio licence.";
        if (request.preset() != null && !contains(presetsFor(request.region(), rules), request.preset())) {
            return "Choose a modem preset allowed in this region.";
        }
        String longName = request.longName() == null ? "" : request.longName().trim();
        String shortName = request.shortName() == null ? "" : request.shortName().trim();
        if (longName.isEmpty()) return "Enter a long name.";
        if (longName.getBytes(StandardCharsets.UTF_8).length > LONG_NAME_BYTES) {
            return "The long name can be up to " + LONG_NAME_BYTES + " bytes.";
        }
        if (shortName.isEmpty()) return "Enter a short name.";
        // Firmware keeps the short name in a 5-byte buffer: four bytes and a terminator.
        if (shortName.codePointCount(0, shortName.length()) > SHORT_NAME_CHARACTERS
                || shortName.getBytes(StandardCharsets.UTF_8).length > SHORT_NAME_CHARACTERS) {
            return "The short name can be up to " + SHORT_NAME_CHARACTERS + " letters or digits.";
        }
        return null;
    }

    /** True when the radio now reports what the user asked for. */
    static boolean matches(Current current, Request request) {
        if (current == null || current.region() != request.region()) return false;
        if (request.preset() != null && (!current.usePreset() || current.preset() != request.preset())) return false;
        return current.longName().equals(request.longName().trim())
                && current.shortName().equals(request.shortName().trim());
    }

    private static boolean contains(List<Preset> presets, int code) {
        for (Preset preset : presets) if (preset.code() == code) return true;
        return false;
    }

    /**
     * Copies a protobuf message, dropping the given top-level fields, so the caller can
     * append new values. Every other field, including repeated and unknown ones, is kept
     * byte for byte. Returns null when the message cannot be read.
     */
    static byte[] withoutFields(byte[] message, Set<Integer> fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(message.length);
        int position = 0;
        while (position < message.length) {
            int start = position;
            long tag = 0;
            int shift = 0;
            while (true) {
                if (position >= message.length || shift > 35) return null;
                int next = message[position++] & 0xff;
                tag |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) break;
                shift += 7;
            }
            int field = (int) (tag >>> 3);
            if (field == 0) return null;
            switch ((int) (tag & 7)) {
                case 0 -> {
                    int bytes = 0;
                    while (true) {
                        if (position >= message.length || ++bytes > 10) return null;
                        if ((message[position++] & 0x80) == 0) break;
                    }
                }
                case 1 -> position += 8;
                case 2 -> {
                    long size = 0;
                    shift = 0;
                    while (true) {
                        if (position >= message.length || shift > 35) return null;
                        int next = message[position++] & 0xff;
                        size |= (long) (next & 0x7f) << shift;
                        if ((next & 0x80) == 0) break;
                        shift += 7;
                    }
                    if (size > message.length - position) return null;
                    position += (int) size;
                }
                case 5 -> position += 4;
                default -> { return null; }
            }
            if (position > message.length) return null;
            if (!fields.contains(field)) output.write(message, start, position - start);
        }
        return output.toByteArray();
    }
}
