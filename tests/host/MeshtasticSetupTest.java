package com.limitedunderground.meshinbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** First-time Meshtastic setup (D-184): tables, validation and the admin message exchange. */
public final class MeshtasticSetupTest {
    private static final long OWN = 17;
    private static final byte[] PUBLIC_KEY = filled(32, 0x11);
    private static final byte[] SESSION_KEY = filled(8, 0x5a);

    public static void main(String[] args) {
        tables();
        bands();
        validation();
        fieldCopy();
        setupExchange();
        refusedExchange();
        System.out.println("Meshtastic setup host checks passed");
    }

    private static void tables() {
        check(MeshtasticSetup.region(0) == null, "UNSET is not a choice");
        for (int amateur : new int[]{27, 28, 33, 34, 35, 36, 37}) {
            check(MeshtasticSetup.region(amateur) == null, "amateur region " + amateur + " is hidden");
        }
        check(MeshtasticSetup.region(15) == null, "deprecated UA_868 is hidden");
        check(MeshtasticSetup.region(1).band().equals("902–928 MHz"), "US band");
        check(MeshtasticSetup.region(3).band().equals("869.4–869.65 MHz"), "EU 868 band");
        List<String> labels = new ArrayList<>();
        for (MeshtasticSetup.Region region : MeshtasticSetup.REGIONS) labels.add(region.label());
        List<String> sorted = new ArrayList<>(labels);
        sorted.sort(String::compareTo);
        check(labels.equals(sorted), "regions are alphabetical");
        check(MeshtasticSetup.PRESETS.get(0).code() == MeshtasticSetup.LONG_FAST, "LongFast is listed first");
    }

    private static void bands() {
        check(MeshtasticSetup.band(0) == null, "an unset region has no band");
        check(MeshtasticSetup.band(27) == null, "a hidden amateur region has no band");
        check(MeshtasticSetup.band(2) == MeshtasticSetup.Band.MHZ_433, "EU 433");
        check(MeshtasticSetup.band(4) == MeshtasticSetup.Band.MHZ_470, "China 470");
        check(MeshtasticSetup.band(13) == MeshtasticSetup.Band.GHZ_2_4, "2.4 GHz");
        check(MeshtasticSetup.band(1) == MeshtasticSetup.Band.MHZ_868_915
                && MeshtasticSetup.band(3) == MeshtasticSetup.Band.MHZ_868_915, "US and EU 868 share a group");
        for (MeshtasticSetup.Region region : MeshtasticSetup.REGIONS) {
            check(MeshtasticSetup.band(region.code()) != null, "every offered region has a band: " + region.label());
            boolean low = region.band().startsWith("43");
            check(low == (MeshtasticSetup.band(region.code()) == MeshtasticSetup.Band.MHZ_433),
                    "the 433 group matches the listed band: " + region.label());
        }
        String toLow = MeshtasticSetup.bandChangeWarning(1, 2);
        check(toLow != null && toLow.contains("868/915 MHz") && toLow.contains("433 MHz"), "915 radio to a 433 region warns");
        check(MeshtasticSetup.bandChangeWarning(1, 13) != null, "915 radio to 2.4 GHz warns");
        check(MeshtasticSetup.bandChangeWarning(2, 1) != null, "433 radio to US warns");
        check(MeshtasticSetup.bandChangeWarning(1, 3) == null, "US to EU 868 does not warn");
        check(MeshtasticSetup.bandChangeWarning(2, 22) == null, "433 to 433 does not warn");
        check(MeshtasticSetup.bandChangeWarning(0, 2) == null, "a fresh radio gives no band to compare");
    }

    private static void validation() {
        Map<Integer, MeshtasticSetup.RegionRule> rules = rules();
        check(problem(0, 0, "Base", "B", rules).equals("Choose your region."), "region is required");
        check(problem(27, 0, "Base", "B", rules).equals("Choose your region."), "amateur region refused");
        check(problem(1, 12, "Base", "B", rules).contains("allowed"), "narrow preset refused in US");
        check(problem(32, 12, "Base", "B", rules) == null, "narrow preset allowed in EU narrow");
        check(problem(32, 0, "Base", "B", rules).contains("allowed"), "LongFast refused where the map forbids it");
        check(problem(1, null, "Base", "B", rules) == null, "keeping custom modem settings is allowed");
        check(problem(2, 0, "Base", "B", Map.of()) == null, "no map means the general list");
        check(problem(1, 0, "x".repeat(25), "B", rules).contains("24 bytes"), "long name byte limit");
        check(problem(1, 0, "é".repeat(12), "B", rules) == null, "24 UTF-8 bytes accepted");
        check(problem(1, 0, "é".repeat(13), "B", rules).contains("24 bytes"), "26 UTF-8 bytes refused");
        check(problem(1, 0, "Base", "ABCDE", rules).contains("up to 4"), "short name character limit");
        check(problem(1, 0, "Base", "ééé", rules).contains("up to 4"), "short name byte limit");
        check(problem(1, 0, "Base", "éé", rules) == null, "four-byte short name accepted");
        check(problem(1, 0, " ", "B", rules).equals("Enter a long name."), "long name required");
        check(MeshtasticSetup.defaultPreset(32, rules) == 12, "firmware default preset for the region");
        check(MeshtasticSetup.defaultPreset(2, rules) == MeshtasticSetup.LONG_FAST, "LongFast without a map entry");
        check(MeshtasticSetup.presetsFor(27, rules).isEmpty(), "licensed-only group offers nothing");
    }

    private static void fieldCopy() {
        byte[] message = new Writer().varint(1, 1).varint(7, 3).packed(103, 5, 6).varint(200, 9).bytes();
        byte[] copy = MeshtasticSetup.withoutFields(message, Set.of(1, 7));
        check(Arrays.equals(copy, new Writer().packed(103, 5, 6).varint(200, 9).bytes()),
                "other fields, repeated and unknown included, are kept byte for byte");
        check(MeshtasticSetup.withoutFields(new byte[]{0x0a, 0x05, 0x01}, Set.of()) == null, "truncated input refused");
    }

    private static void setupExchange() {
        MeshtasticProtocol protocol = new MeshtasticProtocol();
        Radio radio = new Radio();
        handshake(protocol, radio);

        MeshtasticSetup.Current fresh = protocol.setupState();
        check(fresh != null && !fresh.regionSet(), "a fresh radio reports no region");
        check(fresh.longName().equals("Meshtastic 0011") && fresh.shortName().equals("0011"), "own names read");
        check(fresh.txEnabled() && fresh.usePreset() && fresh.preset() == 0, "LoRa values read");
        check(fresh.regionRules().get(32).defaultPreset() == 12, "region preset map read");

        try {
            protocol.applySetup(new MeshtasticSetup.Request(0, 0, "Base", "B"));
            throw new AssertionError("unset region accepted");
        } catch (IllegalArgumentException expected) {
            check(radio.writes.isEmpty(), "a refused request writes nothing");
        }

        MeshtasticSetup.Request request = new MeshtasticSetup.Request(1, 0, " Trail Base ", "TB");
        protocol.applySetup(request);
        check(radio.writes.size() == 1, "setup starts by reading the LoRa settings");
        Packet keyRequest = Packet.read(radio.writes.get(0));
        check(keyRequest.to == OWN && keyRequest.port == 6 && keyRequest.wantResponse && keyRequest.wantAck,
                "admin request addressed to the radio's own node");
        check(keyRequest.admin.number(5) == 5 && !keyRequest.admin.has(101), "get_config_request LORA_CONFIG, no key yet");

        protocol.onBytes(adminReply(keyRequest.id, new Writer()
                .bytes(6, new Writer().bytes(6, lora(0)).bytes())
                .bytes(101, SESSION_KEY).bytes()));
        check(radio.writes.size() == 5, "begin, set_owner, set_config and commit follow the reply");
        Packet begin = Packet.read(radio.writes.get(1));
        Packet owner = Packet.read(radio.writes.get(2));
        Packet config = Packet.read(radio.writes.get(3));
        Packet commit = Packet.read(radio.writes.get(4));
        check(begin.admin.number(64) == 1 && commit.admin.number(65) == 1, "edit transaction");
        for (Packet packet : List.of(begin, owner, config, commit)) {
            check(Arrays.equals(packet.admin.binary(101), SESSION_KEY), "every change carries the session key");
            check(packet.to == OWN && !packet.wantResponse && packet.wantAck, "changes go to the own node with an ack");
        }
        Fields user = Fields.read(owner.admin.binary(32));
        check(user.string(2).equals("Trail Base") && user.string(3).equals("TB"), "trimmed names written");
        check(user.string(1).equals("!00000011") && Arrays.equals(user.binary(8), PUBLIC_KEY)
                && user.number(5) == 9, "the rest of the owner record is kept");
        Fields loraSent = Fields.read(Fields.read(config.admin.binary(34)).binary(6));
        check(loraSent.number(7) == 1 && loraSent.number(1) == 1 && loraSent.number(2) == 0, "region and preset written");
        check(loraSent.number(8) == 3 && loraSent.number(9) == 1 && loraSent.number(200) == 9,
                "hop limit, transmit enabled and unknown fields kept");
        check(Arrays.equals(loraSent.binary(103), new byte[]{5, 6}), "ignore list kept");
        check(radio.applied && radio.failure == null && radio.setupFailure == null, "setup handed to the radio");

        int before = radio.writes.size();
        protocol.refreshSetup();
        check(radio.writes.size() == before + 2, "read back asks for LoRa settings and owner");
        Packet loraRead = Packet.read(radio.writes.get(before));
        Packet ownerRead = Packet.read(radio.writes.get(before + 1));
        check(ownerRead.admin.number(3) == 1, "get_owner_request");
        protocol.onBytes(adminReply(loraRead.id, new Writer().bytes(6, new Writer().bytes(6, lora(1)).bytes()).bytes()));
        check(!MeshtasticSetup.matches(protocol.setupState(), request), "names not yet confirmed");
        protocol.onBytes(adminReply(ownerRead.id, new Writer().bytes(4, user("Trail Base", "TB")).bytes()));
        check(MeshtasticSetup.matches(protocol.setupState(), request), "radio now reports the requested setup");
        check(radio.changes >= 2, "each read back is reported");
        check(radio.writes.size() == before + 2, "a read back during SENT does not resend changes");

        protocol.onBytes(adminReply(0x7777, new Writer().bytes(6, new Writer().bytes(6, lora(5)).bytes()).bytes()));
        check(protocol.setupState().region() == 1, "an unrequested admin reply is ignored");
    }

    private static void refusedExchange() {
        MeshtasticProtocol protocol = new MeshtasticProtocol();
        Radio radio = new Radio();
        handshake(protocol, radio);
        protocol.applySetup(new MeshtasticSetup.Request(3, null, "Meshtastic 0011", "0011"));
        Packet request = Packet.read(radio.writes.get(0));
        protocol.onBytes(routing(request.id, 36));
        check(radio.setupFailure != null && radio.setupFailure.contains("session key"), "expired key reported");
        check(radio.writes.size() == 1 && radio.failure == null, "nothing more is sent and the link stays up");

        protocol.applySetup(new MeshtasticSetup.Request(3, null, "Meshtastic 0011", "0011"));
        Packet retry = Packet.read(radio.writes.get(1));
        protocol.onBytes(routing(retry.id, 0));
        protocol.onBytes(adminReply(retry.id, new Writer().bytes(6, new Writer().bytes(6, lora(0)).bytes()).bytes()));
        check(radio.writes.size() == 5, "only begin, the LoRa change and commit are sent when names are unchanged");
        check(Packet.read(radio.writes.get(2)).admin.number(64) == 1, "begin first");
        Fields loraSent = Fields.read(Fields.read(Packet.read(radio.writes.get(3)).admin.binary(34)).binary(6));
        check(loraSent.number(7) == 3 && loraSent.number(1) == 1 && !loraSent.has(2),
                "keeping the modem settings leaves the preset fields as read");
        check(Packet.read(radio.writes.get(4)).admin.number(65) == 1, "commit last");
    }

    private static void handshake(MeshtasticProtocol protocol, Radio radio) {
        protocol.open(radio, radio);
        long nonce = Fields.read(radio.writes.get(0)).number(3);
        radio.writes.clear();
        protocol.onBytes(new Writer().bytes(3, new Writer().varint(1, OWN).bytes()).bytes());
        protocol.onBytes(new Writer().bytes(19, regionMap()).bytes());
        protocol.onBytes(new Writer().bytes(4, new Writer().varint(1, OWN)
                .bytes(2, user("Meshtastic 0011", "0011")).bytes()).bytes());
        protocol.onBytes(new Writer().bytes(5, new Writer().bytes(6, lora(0)).bytes()).bytes());
        protocol.onBytes(new Writer().bytes(10, new Writer().bytes(2, new byte[0]).varint(3, 1).bytes()).bytes());
        protocol.onBytes(new Writer().varint(7, nonce).bytes());
        check(radio.ready, "handshake completes");
        radio.writes.clear();
    }

    private static byte[] lora(int region) {
        Writer writer = new Writer().varint(1, 1);
        if (region != 0) writer.varint(7, region);
        return writer.varint(8, 3).varint(9, 1).packed(103, 5, 6).varint(200, 9).bytes();
    }

    private static byte[] user(String longName, String shortName) {
        return new Writer().string(1, "!00000011").string(2, longName).string(3, shortName)
                .varint(5, 9).bytes(8, PUBLIC_KEY).bytes();
    }

    private static byte[] regionMap() {
        byte[] standard = new Writer().packed(1, 0, 7, 9, 3, 4, 16, 5, 6, 8).bytes();
        byte[] narrow = new Writer().packed(1, 12, 13).varint(2, 12).bytes();
        byte[] licensed = new Writer().packed(1, 0).varint(3, 1).bytes();
        return new Writer().bytes(1, standard).bytes(1, narrow).bytes(1, licensed)
                .bytes(2, new Writer().varint(1, 1).bytes())
                .bytes(2, new Writer().varint(1, 32).varint(2, 1).bytes())
                .bytes(2, new Writer().varint(1, 27).varint(2, 2).bytes()).bytes();
    }

    private static Map<Integer, MeshtasticSetup.RegionRule> rules() {
        Map<Integer, MeshtasticSetup.RegionRule> rules = new HashMap<>();
        rules.put(1, new MeshtasticSetup.RegionRule(List.of(0, 7, 9, 3, 4, 16, 5, 6, 8), 0, false));
        rules.put(32, new MeshtasticSetup.RegionRule(List.of(12, 13), 12, false));
        rules.put(27, new MeshtasticSetup.RegionRule(List.of(0), 0, true));
        return rules;
    }

    private static String problem(int region, Integer preset, String longName, String shortName,
                                  Map<Integer, MeshtasticSetup.RegionRule> rules) {
        return MeshtasticSetup.validate(new MeshtasticSetup.Request(region, preset, longName, shortName), rules);
    }

    private static byte[] adminReply(long requestId, byte[] admin) {
        byte[] data = new Writer().varint(1, 6).bytes(2, admin).fixed32(6, requestId).bytes();
        return new Writer().bytes(2, new Writer().fixed32(1, OWN).fixed32(2, OWN).bytes(4, data).bytes()).bytes();
    }

    private static byte[] routing(long requestId, int error) {
        byte[] routing = new Writer().varint(3, error).bytes();
        byte[] data = new Writer().varint(1, 5).bytes(2, routing).fixed32(6, requestId).bytes();
        return new Writer().bytes(2, new Writer().fixed32(1, OWN).fixed32(2, OWN).bytes(4, data).bytes()).bytes();
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void check(boolean outcome, String label) {
        if (!outcome) throw new AssertionError(label);
    }

    private record Packet(long to, long id, int port, boolean wantResponse, boolean wantAck, Fields admin) {
        static Packet read(byte[] toRadio) {
            Fields packet = Fields.read(Fields.read(toRadio).binary(1));
            Fields data = Fields.read(packet.binary(4));
            return new Packet(packet.number(2), packet.number(6), (int) data.number(1), data.number(3) == 1,
                    packet.number(10) == 1, Fields.read(data.binary(2)));
        }
    }

    /** A small protobuf reader for checking written frames. */
    private static final class Fields {
        private final Map<Integer, Object> values = new LinkedHashMap<>();

        static Fields read(byte[] bytes) {
            Fields fields = new Fields();
            int[] at = {0};
            while (at[0] < bytes.length) {
                long tag = varint(bytes, at);
                int field = (int) (tag >>> 3);
                switch ((int) (tag & 7)) {
                    case 0 -> fields.values.put(field, varint(bytes, at));
                    case 2 -> {
                        int size = (int) varint(bytes, at);
                        fields.values.put(field, Arrays.copyOfRange(bytes, at[0], at[0] + size));
                        at[0] += size;
                    }
                    case 5 -> {
                        long value = 0;
                        for (int i = 0; i < 4; i++) value |= (bytes[at[0] + i] & 0xffL) << (8 * i);
                        fields.values.put(field, value);
                        at[0] += 4;
                    }
                    default -> throw new AssertionError("unexpected wire type");
                }
            }
            return fields;
        }

        boolean has(int field) { return values.containsKey(field); }
        long number(int field) { return values.get(field) instanceof Long value ? value : 0; }
        byte[] binary(int field) { return values.get(field) instanceof byte[] value ? value : new byte[0]; }
        String string(int field) { return new String(binary(field), StandardCharsets.UTF_8); }

        private static long varint(byte[] bytes, int[] at) {
            long value = 0;
            for (int shift = 0; ; shift += 7) {
                int next = bytes[at[0]++] & 0xff;
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        Writer varint(int field, long value) {
            write(field << 3);
            write(value);
            return this;
        }

        Writer bytes(int field, byte[] value) {
            write((field << 3) | 2);
            write(value.length);
            output.write(value, 0, value.length);
            return this;
        }

        Writer string(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }

        Writer packed(int field, int... values) {
            Writer inner = new Writer();
            for (int value : values) inner.write(value);
            return bytes(field, inner.bytes());
        }

        Writer fixed32(int field, long value) {
            write((field << 3) | 5);
            for (int i = 0; i < 4; i++) output.write((int) (value >>> (8 * i)) & 0xff);
            return this;
        }

        byte[] bytes() { return output.toByteArray(); }

        private void write(long value) {
            while ((value & ~0x7fL) != 0) {
                output.write((int) (value & 0x7f) | 0x80);
                value >>>= 7;
            }
            output.write((int) value);
        }
    }

    private static final class Radio implements RadioProtocol.Link, RadioProtocol.Events {
        final List<byte[]> writes = new ArrayList<>();
        boolean ready;
        boolean applied;
        int changes;
        String failure;
        String setupFailure;

        @Override public void write(byte[] frame) { writes.add(frame.clone()); }
        @Override public void read() {}
        @Override public void ready() { ready = true; }
        @Override public void destinations(List<RadioProtocol.Destination> values) {}
        @Override public void message(RadioProtocol.Destination replyTo, String senderId, String author,
                                      String body, long timestampMillis) {}
        @Override public void notice(String value) {}
        @Override public void failed(String value) { failure = value; }
        @Override public void setupChanged() { changes++; }
        @Override public void setupApplied() { applied = true; }
        @Override public void setupFailed(String value) { setupFailure = value; }
    }
}
