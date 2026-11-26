package com.limitedunderground.meshinbox;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * First-time MeshCore Companion setup (D-184): a regional radio preset, the node name and an
 * advert. Frame layouts follow meshcore-dev/MeshCore examples/companion_radio/MyMesh.cpp.
 */
final class MeshCoreSetup {
    static final byte CMD_SEND_SELF_ADVERT = 7;
    static final byte CMD_SET_ADVERT_NAME = 8;
    static final byte CMD_SET_RADIO_PARAMS = 11;
    /** node_name is a 32-byte buffer in the firmware: 31 bytes and a terminator. */
    static final int NAME_BYTES = 31;
    /** DEVICE_INFO byte 80 carries the client repeat flag from firmware version code 9. */
    static final int REPEAT_FIRMWARE = 9;

    private static final int SELF_FREQ = 48;
    private static final int SELF_BW = 52;
    private static final int SELF_SF = 56;
    private static final int SELF_CR = 57;
    private static final int SELF_NAME = 58;
    private static final int DEVICE_REPEAT = 80;

    /** Frequency in kHz and bandwidth in Hz, as the companion protocol carries them. */
    record Preset(String label, int frequencyKhz, int bandwidthHz, int spreadingFactor, int codingRate) {
        String values() {
            return String.format(java.util.Locale.ROOT, "%.3f MHz · BW %s kHz · SF%d · CR%d",
                    frequencyKhz / 1000.0, kilohertz(bandwidthHz), spreadingFactor, codingRate);
        }
    }

    /** What the radio reported. Repeat is null when the firmware does not report it. */
    record Current(int frequencyKhz, int bandwidthHz, int spreadingFactor, int codingRate, String name,
                   Boolean repeat) {
        boolean matches(Preset preset) {
            return preset.frequencyKhz() == frequencyKhz && preset.bandwidthHz() == bandwidthHz
                    && preset.spreadingFactor() == spreadingFactor && preset.codingRate() == codingRate;
        }
    }

    enum Advert { NONE, NEARBY, MESH }

    /** What the user chose. A null preset keeps the radio settings as they are. */
    record Request(Preset preset, String name, Advert advert) {}

    /**
     * A short hand-checked list (D-184). USA / Canada is the "Recommended" preset in the MeshCore
     * FAQ; EU / UK (Narrow) is the firmware's built-in default (platformio.ini); Australia and
     * New Zealand (Narrow) are from the community list at api.meshcore.nz. Each sits inside its
     * country's band with its full bandwidth.
     */
    static final List<Preset> PRESETS = List.of(
            new Preset("USA / Canada", 910_525, 62_500, 7, 5),
            new Preset("EU / UK (Narrow)", 869_618, 62_500, 8, 8),
            new Preset("Australia", 915_800, 250_000, 10, 5),
            new Preset("New Zealand (Narrow)", 917_375, 62_500, 7, 5));

    private MeshCoreSetup() {}

    /** Reads radio settings and name from a RESP_CODE_SELF_INFO frame, or null when it is too short. */
    static Current parseSelfInfo(byte[] frame, Boolean repeat) {
        if (frame.length < SELF_NAME) return null;
        int end = frame.length;
        for (int i = SELF_NAME; i < frame.length; i++) {
            if (frame[i] == 0) {
                end = i;
                break;
            }
        }
        String name = new String(frame, SELF_NAME, end - SELF_NAME, StandardCharsets.UTF_8);
        return new Current(readInt(frame, SELF_FREQ), readInt(frame, SELF_BW), frame[SELF_SF] & 0xff,
                frame[SELF_CR] & 0xff, name, repeat);
    }

    /** The client repeat flag from a RESP_CODE_DEVICE_INFO frame, or null when not reported. */
    static Boolean parseRepeat(byte[] frame) {
        if (frame.length <= DEVICE_REPEAT || (frame[1] & 0xff) < REPEAT_FIRMWARE) return null;
        return frame[DEVICE_REPEAT] != 0;
    }

    /** CMD_SET_RADIO_PARAMS, sending the radio's own repeat flag back so it is not switched off. */
    static byte[] setRadio(Preset preset, Boolean repeat) {
        ByteBuffer frame = ByteBuffer.allocate(repeat == null ? 11 : 12).order(ByteOrder.LITTLE_ENDIAN)
                .put(CMD_SET_RADIO_PARAMS).putInt(preset.frequencyKhz()).putInt(preset.bandwidthHz())
                .put((byte) preset.spreadingFactor()).put((byte) preset.codingRate());
        if (repeat != null) frame.put((byte) (repeat ? 1 : 0));
        return frame.array();
    }

    static byte[] setName(String name) {
        byte[] text = name.trim().getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[1 + text.length];
        frame[0] = CMD_SET_ADVERT_NAME;
        System.arraycopy(text, 0, frame, 1, text.length);
        return frame;
    }

    static byte[] advert(Advert advert) {
        return new byte[]{CMD_SEND_SELF_ADVERT, (byte) (advert == Advert.MESH ? 1 : 0)};
    }

    /** Returns a user-facing problem with the request, or null when it can be sent. */
    static String validate(Request request, Current current) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) return "Enter a name for this radio.";
        if (name.indexOf('\0') >= 0) return "The name cannot contain a null character.";
        if (name.getBytes(StandardCharsets.UTF_8).length > NAME_BYTES) {
            return "The name can be up to " + NAME_BYTES + " bytes.";
        }
        if (request.preset() != null && !PRESETS.contains(request.preset())) return "Choose a listed preset.";
        if (request.advert() == null) return "Choose whether to announce the radio.";
        return null;
    }

    /** True when the radio now reports what the user asked for. */
    static boolean matches(Current current, Request request) {
        if (current == null) return false;
        if (request.preset() != null && !current.matches(request.preset())) return false;
        return current.name().equals(request.name().trim());
    }

    /** Hardware band groups, as for Meshtastic: below 500 MHz, 863–928 MHz and 2.4 GHz. */
    static String band(int frequencyKhz) {
        if (frequencyKhz < 500_000) return frequencyKhz < 450_000 ? "433 MHz" : "470 MHz";
        if (frequencyKhz < 1_000_000) return "868/915 MHz";
        return "2.4 GHz";
    }

    /** A warning when the preset is in another band group from the radio's current frequency. */
    static String bandChangeWarning(Current current, Preset preset) {
        if (current == null || preset == null) return null;
        String now = band(current.frequencyKhz());
        String next = band(preset.frequencyKhz());
        if (now.equals(next)) return null;
        return "This radio is on the " + now + " band, and " + preset.label() + " uses " + next
                + ". A radio only works in the band its hardware is built for. Save anyway?";
    }

    static String describe(Current current) {
        for (Preset preset : PRESETS) if (current.matches(preset)) return preset.label() + " · " + preset.values();
        return new Preset("", current.frequencyKhz(), current.bandwidthHz(), current.spreadingFactor(),
                current.codingRate()).values();
    }

    private static String kilohertz(int hertz) {
        return hertz % 1000 == 0 ? Integer.toString(hertz / 1000)
                : String.format(java.util.Locale.ROOT, "%.1f", hertz / 1000.0);
    }

    private static int readInt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
