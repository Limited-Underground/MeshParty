package com.limitedunderground.meshinbox;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** First-time MeshCore setup (D-184): presets, frame bytes, SELF_INFO and DEVICE_INFO parsing. */
public final class MeshCoreSetupTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final MeshCoreSetup.Preset USA = MeshCoreSetup.PRESETS.get(0);
    private static final MeshCoreSetup.Preset EU = MeshCoreSetup.PRESETS.get(1);

    public static void main(String[] args) {
        presets();
        frames();
        parsing();
        validation();
        bands();
        System.out.println("MeshCore setup host checks passed");
    }

    private static void presets() {
        check(USA.label().equals("USA / Canada") && USA.frequencyKhz() == 910_525 && USA.bandwidthHz() == 62_500
                && USA.spreadingFactor() == 7 && USA.codingRate() == 5, "USA / Canada matches the MeshCore FAQ");
        check(EU.frequencyKhz() == 869_618 && EU.spreadingFactor() == 8 && EU.codingRate() == 8,
                "EU / UK (Narrow) matches the firmware default");
        check(USA.values().equals("910.525 MHz · BW 62.5 kHz · SF7 · CR5"), "preset text");
        check(MeshCoreSetup.PRESETS.get(2).values().equals("915.800 MHz · BW 250 kHz · SF10 · CR5"), "whole kHz bandwidth");
        for (MeshCoreSetup.Preset preset : MeshCoreSetup.PRESETS) {
            int half = preset.bandwidthHz() / 2;
            int low = preset.frequencyKhz() * 1000 - half;
            int high = preset.frequencyKhz() * 1000 + half;
            boolean inBand = preset.label().startsWith("EU")
                    ? low >= 869_400_000 && high <= 869_650_000
                    : preset.label().startsWith("USA") ? low >= 902_000_000 && high <= 928_000_000
                    : low >= 915_000_000 && high <= 928_000_000;
            check(inBand, "preset fits its band with its full bandwidth: " + preset.label());
            check(preset.spreadingFactor() >= 5 && preset.spreadingFactor() <= 12
                    && preset.codingRate() >= 5 && preset.codingRate() <= 8, "firmware ranges: " + preset.label());
        }
    }

    private static void frames() {
        check(HEX.formatHex(MeshCoreSetup.setRadio(USA, null)).equals("0bbde40d0024f400000705"),
                "CMD_SET_RADIO_PARAMS: kHz and Hz little-endian, SF, CR, no repeat byte on old firmware");
        check(HEX.formatHex(MeshCoreSetup.setRadio(USA, false)).equals("0bbde40d0024f40000070500"),
                "repeat off is sent back as off");
        check(HEX.formatHex(MeshCoreSetup.setRadio(EU, true)).endsWith("080801"), "repeat on is kept on");
        check(HEX.formatHex(MeshCoreSetup.setName(" Base ")).equals("0842617365"), "CMD_SET_ADVERT_NAME, trimmed, no NUL");
        check(HEX.formatHex(MeshCoreSetup.advert(MeshCoreSetup.Advert.NEARBY)).equals("0700"), "zero-hop advert");
        check(HEX.formatHex(MeshCoreSetup.advert(MeshCoreSetup.Advert.MESH)).equals("0701"), "flood advert");
    }

    private static void parsing() {
        MeshCoreSetup.Current current = MeshCoreSetup.parseSelfInfo(selfInfo(869_618, 62_500, 8, 8, "a1b2c3d4"), null);
        check(current != null && current.frequencyKhz() == 869_618 && current.bandwidthHz() == 62_500
                && current.spreadingFactor() == 8 && current.codingRate() == 8, "radio values at offsets 48-57");
        check(current.name().equals("a1b2c3d4") && current.repeat() == null, "name from offset 58, repeat unknown");
        check(current.matches(EU) && !current.matches(USA), "preset match");
        check(MeshCoreSetup.describe(current).startsWith("EU / UK (Narrow)"), "a known preset is named");
        check(MeshCoreSetup.describe(MeshCoreSetup.parseSelfInfo(selfInfo(915_000, 125_000, 9, 5, "x"), null))
                .equals("915.000 MHz · BW 125 kHz · SF9 · CR5"), "an unknown setting is shown as values");
        check(MeshCoreSetup.parseSelfInfo(new byte[40], null) == null, "a short frame is refused");
        check(MeshCoreSetup.parseSelfInfo(selfInfo(1, 1, 5, 5, ""), null).name().isEmpty(), "empty name");

        check(MeshCoreSetup.parseRepeat(deviceInfo(9, true)) == Boolean.TRUE, "repeat on from v9");
        check(MeshCoreSetup.parseRepeat(deviceInfo(10, false)) == Boolean.FALSE, "repeat off from v10");
        check(MeshCoreSetup.parseRepeat(deviceInfo(8, true)) == null, "v8 does not report repeat");
        check(MeshCoreSetup.parseRepeat(new byte[]{13, 9, 0, 0}) == null, "short DEVICE_INFO");
    }

    private static void validation() {
        MeshCoreSetup.Current current = MeshCoreSetup.parseSelfInfo(selfInfo(869_618, 62_500, 8, 8, "Old"), null);
        check(problem(USA, "Base", current) == null, "valid request");
        check(problem(null, "Base", current) == null, "keeping the radio settings is allowed");
        check(problem(USA, " ", current).equals("Enter a name for this radio."), "name required");
        check(problem(USA, "x".repeat(32), current).contains("31 bytes"), "name byte limit");
        check(problem(USA, "é".repeat(15) + "x", current) == null, "31 UTF-8 bytes accepted");
        check(problem(new MeshCoreSetup.Preset(null, "Mine", 433_000, 62_500, 8, 8), "Base", current)
                .equals("Choose a listed preset."), "only listed presets");
        check(MeshCoreSetup.matches(MeshCoreSetup.parseSelfInfo(selfInfo(910_525, 62_500, 7, 5, "Base"), null),
                new MeshCoreSetup.Request(USA, " Base ", MeshCoreSetup.Advert.NEARBY)), "read-back match");
        check(!MeshCoreSetup.matches(current, new MeshCoreSetup.Request(USA, "Old", MeshCoreSetup.Advert.NONE)),
                "radio settings not applied yet");
        check(MeshCoreSetup.matches(current, new MeshCoreSetup.Request(null, "Old", MeshCoreSetup.Advert.NONE)),
                "keep current matches");
    }

    private static void bands() {
        MeshCoreSetup.Current eu = MeshCoreSetup.parseSelfInfo(selfInfo(869_618, 62_500, 8, 8, "a"), null);
        MeshCoreSetup.Current low = MeshCoreSetup.parseSelfInfo(selfInfo(433_650, 62_500, 8, 8, "a"), null);
        check(MeshCoreSetup.bandChangeWarning(eu, USA) == null, "868 to 915 does not warn");
        check(MeshCoreSetup.bandChangeWarning(eu, null) == null, "keeping settings does not warn");
        String warning = MeshCoreSetup.bandChangeWarning(low, USA);
        check(warning != null && warning.contains("433 MHz") && warning.contains("868/915 MHz"), "433 radio to USA warns");
    }

    private static String problem(MeshCoreSetup.Preset preset, String name, MeshCoreSetup.Current current) {
        return MeshCoreSetup.validate(new MeshCoreSetup.Request(preset, name, MeshCoreSetup.Advert.NEARBY), current);
    }

    /** A SELF_INFO frame laid out as companion_radio/MyMesh.cpp writes it. */
    private static byte[] selfInfo(int frequencyKhz, int bandwidthHz, int sf, int cr, String name) {
        byte[] text = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(58 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.put((byte) 5).put((byte) 1).put((byte) 22).put((byte) 22).put(new byte[32])
                .putInt(0).putInt(0).put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0)
                .putInt(frequencyKhz).putInt(bandwidthHz).put((byte) sf).put((byte) cr).put(text);
        return frame.array();
    }

    /** A DEVICE_INFO frame: code, version code, contacts/2, channels, PIN, date, maker, version, repeat, path hash. */
    private static byte[] deviceInfo(int version, boolean repeat) {
        ByteBuffer frame = ByteBuffer.allocate(82);
        frame.put((byte) 13).put((byte) version).put((byte) 50).put((byte) 8).put(new byte[4])
                .put(new byte[12]).put(new byte[40]).put(new byte[20]).put((byte) (repeat ? 1 : 0)).put((byte) 0);
        return frame.array();
    }

    private static void check(boolean outcome, String label) {
        if (!outcome) throw new AssertionError(label);
    }
}
