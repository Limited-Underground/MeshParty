package com.limitedunderground.meshinbox;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Checks how a MeshCore contact frame becomes a node in the nodes list (0.2.2). */
public final class MeshCoreNodesTest {
    public static void main(String[] args) {
        long updated = 1_700_000_000L;
        RadioProtocol.Node repeater = MeshCoreProtocol.contactNode(contact(2, 3, "Hilltop", 1_600_000_000L, updated),
                "aa".repeat(32), "aaaaaaaaaaaa", "Hilltop");
        check(repeater.kind() == RadioProtocol.Node.REPEATER, "type 2 is a repeater");
        check(repeater.hops() == 3, "path length is the hop count");
        check(repeater.snr() == null, "MeshCore keeps no signal per contact");
        check(repeater.lastHeardMillis() == updated * 1000L, "last heard is when the radio updated the contact");
        check(repeater.shortId().equals("aaaaaaaa"), "short ID is the first four key bytes");

        RadioProtocol.Node flood = MeshCoreProtocol.contactNode(contact(1, -1, "Ana", 0, 0), "bb".repeat(32),
                "bbbbbbbbbbbb", "Ana");
        check(flood.kind() == RadioProtocol.Node.COMPANION && flood.hops() == null, "unknown path has no hop count");
        check(flood.lastHeardMillis() == 0, "an unset radio clock reads as unknown");

        RadioProtocol.Node direct = MeshCoreProtocol.contactNode(contact(9, 0, "Odd", 1_650_000_000L, 0),
                "cc".repeat(32), "cccccccccccc", "Odd");
        check(direct.kind() == RadioProtocol.Node.COMPANION && direct.hops() == 0, "unknown type reads as companion; 0 is direct");
        check(direct.lastHeardMillis() == 1_650_000_000_000L, "without an update time the advert time is used");
        System.out.println("MeshCore nodes host checks passed");
    }

    /** RESP_CODE_CONTACT: key, type, flags, path length, path, name, advert time, lat, lon, last modified. */
    private static byte[] contact(int type, int pathLength, String name, long advertTime, long updated) {
        ByteBuffer frame = ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN);
        frame.put((byte) 3);
        frame.put(new byte[32]);
        frame.put((byte) type).put((byte) 0).put((byte) pathLength);
        frame.put(new byte[64]);
        byte[] label = new byte[32];
        byte[] text = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(text, 0, label, 0, text.length);
        frame.put(label);
        frame.putInt((int) advertTime).putInt(0).putInt(0).putInt((int) updated);
        return frame.array();
    }

    private static void check(boolean outcome, String label) {
        if (!outcome) throw new AssertionError(label);
    }
}
