package com.limitedunderground.meshinbox;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Exercises the messaging protocol on a Java host with literal radio frames. */
public final class MeshtasticProtocolTest {
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) {
        MeshtasticProtocol protocol = new MeshtasticProtocol();
        SimulatedRadio radio = new SimulatedRadio();
        protocol.open(radio, radio);
        check(radio.reads == 1, "initial read queued after config write");
        byte[] configRequest = radio.writes.get(0);
        check(configRequest[0] == 0x18, "ToRadio want_config_id field");
        check(radio.destinations.isEmpty(), "no routes during configuration");
        protocol.onBytes(hex("38ac9e04")); // An unrelated config_complete_id (69420).
        check(!radio.ready, "a stale completion cannot enable sending");

        protocol.onBytes(hex("1a020811")); // MyNodeInfo node 17.
        protocol.onBytes(hex("520412001801")); // Enabled unnamed primary slot 0.
        protocol.onBytes(hex("520b080112051a034f70731802")); // Enabled secondary slot 1 named Ops.
        protocol.onBytes(hex("2a0432020801")); // LoRa use_preset=true and default LONG_FAST.
        protocol.onBytes(hex("22090822120512034a6f6f")); // Node 34, long name Joo.
        byte[] configComplete = configRequest.clone();
        configComplete[0] = 0x38;
        protocol.onBytes(configComplete);
        check(radio.ready && radio.destinations.size() == 3, "config exposes two channels and one peer");
        check(radio.destinations.get(0).label().equals("LongFast"), "primary preset name");
        check(radio.nodes.size() == 1 && radio.nodes.get(0).name().equals("Joo")
                && radio.nodes.get(0).shortId().equals("!00000022"), "node list has the peer, not the own node");
        check(radio.nodes.get(0).lastHeardMillis() == 0 && radio.nodes.get(0).snr() == null
                && radio.nodes.get(0).hops() == null, "a node database entry without values reports none");

        int beforeWake = radio.reads;
        protocol.onAvailable();
        check(radio.reads == beforeWake, "notification does not overlap a read");
        protocol.onBytes(new byte[0]);
        check(radio.reads == beforeWake + 1, "notification racing an empty read is rechecked");

        protocol.onBytes(hex("12190d2200000015ffffffff180122060801120248693d64000000"));
        check(radio.body.equals("Hi") && radio.reply.equals(radio.destinations.get(1)), "channel inbox and reply route");
        check(radio.receivedAt == 100_000L && radio.author.equals("Joo"), "radio time and author");
        check("node:00000022".equals(radio.senderId), "channel message exposes the sender's node ID");
        check(radio.nodes.get(0).lastHeardMillis() > 1_700_000_000_000L, "a packet marks the node as heard now");

        // Channel text with rx_snr 6.25, hop_limit 1 and hop_start 3.
        protocol.onBytes(hex("121d0d2200000015ffffffff18012206080112024869450000c84048017803"));
        check(radio.nodes.get(0).snr() == 6.25f && radio.nodes.get(0).hops() == 2, "packet signal and hops");
        // Node database entry for node 0x44: snr 10, last heard 1,700,000,000 s, hops_away 2.
        protocol.onBytes(hex("220e084425000020412d00f153654802"));
        RadioProtocol.Node far = radio.nodes.stream().filter(n -> n.shortId().equals("!00000044")).findFirst().orElseThrow();
        check(far.name().equals("!00000044") && far.snr() == 10f && far.hops() == 2
                && far.lastHeardMillis() == 1_700_000_000_000L, "node database signal, hops and last heard");
        check(radio.destinations.size() == 3, "a heard node without a user record adds no route");

        protocol.onBytes(hex("12170d220000001511000000220608011202444d3d64000000"));
        check(radio.body.equals("DM") && radio.reply.equals(radio.destinations.get(2)), "direct message reply route");
        check("node:00000022".equals(radio.senderId), "direct message exposes the same sender ID");
        protocol.onBytes(hex("12170d2200000015330000002206080112024e6f3d64000000"));
        check(radio.body.equals("DM"), "ignores a direct message addressed to another node");

        int writesBeforeRejectedSend = radio.writes.size();
        try {
            protocol.send(new RadioProtocol.Destination("channel:2", "Unknown", true), "Hi");
            throw new AssertionError("unknown destination was accepted");
        } catch (IllegalArgumentException expected) {
            check(radio.writes.size() == writesBeforeRejectedSend, "unknown destination does not write");
        }

        long channelSendId = protocol.send(radio.destinations.get(1), "Hi");
        byte[] channelSend = radio.writes.get(radio.writes.size() - 1);
        check(HEX.formatHex(channelSend).matches("0a1615ffffffff1801220608011202486935[0-9a-f]{8}5001"),
                "channel send writes broadcast, explicit slot, text and want_ack");
        int channelPacketId = ByteBuffer.wrap(channelSend).order(ByteOrder.LITTLE_ENDIAN).getInt(18);
        protocol.onBytes(routingAck(channelPacketId));
        check(radio.notice.contains("relay acknowledgement"), "relay acknowledgement is distinguished from recipient receipt");
        check(channelPacketId == (int) channelSendId && radio.deliveries.get(channelSendId) == RadioProtocol.SENT,
                "a relayed channel message is reported as sent");

        long directSendId = protocol.send(radio.destinations.get(2), "Hi");
        byte[] directSend = radio.writes.get(radio.writes.size() - 1);
        check(HEX.formatHex(directSend).matches("0a1615220000001800220608011202486935[0-9a-f]{8}5001"),
                "DM send addresses the selected Meshtastic node");
        protocol.onBytes(routing((int) directSendId, 0x11, 0));
        check(radio.deliveries.get(directSendId) == RadioProtocol.SENT, "a relay by another node is only sent");
        protocol.onBytes(routing((int) directSendId, 0x22, 0));
        check(radio.deliveries.get(directSendId) == RadioProtocol.DELIVERED, "the recipient's acknowledgement is delivered");

        long lostSendId = protocol.send(radio.destinations.get(2), "Lost");
        protocol.onBytes(routing((int) lostSendId, 0x11, 5));
        check(radio.deliveries.get(lostSendId) == RadioProtocol.FAILED, "no acknowledgement after retries fails");
        long quietChannelId = protocol.send(radio.destinations.get(1), "Quiet");
        protocol.onBytes(routing((int) quietChannelId, 0x11, 5));
        check(radio.deliveries.get(quietChannelId) == RadioProtocol.SENT, "a channel message with no relay heard is still sent");

        String ownKey = "11".repeat(32);
        String peerKey = "22".repeat(32);
        protocol.onBytes(hex("2226081112224220" + ownKey));
        check(radio.destinations.size() == 2, "public-key radio does not offer a keyless peer");
        protocol.onBytes(hex("222b0822122712034a6f6f4220" + peerKey));
        RadioProtocol.Destination knownPeer = radio.destinations.get(2);
        protocol.onBytes(hex("12170d220000001511000000220608011202444d3d64000000"));
        check("node:00000022".equals(radio.senderId) && knownPeer.equals(radio.reply),
                "learning a public key changes the route without changing the blockable sender ID");
        protocol.onBytes(hex("12370d2200000015ffffffff222b0804122712034e65774220" + "33".repeat(32)));
        check(radio.destinations.get(2).equals(knownPeer), "an advertisement cannot replace a known peer key");
        protocol.send(knownPeer, "Hi");
        byte[] encryptedSendRequest = radio.writes.get(radio.writes.size() - 1);
        check(HEX.formatHex(encryptedSendRequest).endsWith("820120" + peerKey + "880101"),
                "public-key send requests the expected recipient key and no encryption fallback");

        protocol.onBytes(hex("12ff"));
        check(radio.notice.contains("unreadable"), "malformed envelope is reported and drain continues");
        protocol.close();
        check(radio.failure == null, "malformed envelope did not close the link");
        System.out.println("Meshtastic protocol host checks passed");
    }

    private static byte[] routingAck(int id) {
        byte[] packet = hex("12170d220000001511000000220b0805120218003500000000");
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(packet.length - 4, id);
        return packet;
    }

    /** A routing reply from a node for a request ID, with an error code (0 = acknowledgement). */
    private static byte[] routing(int id, int from, int error) {
        byte[] packet = hex("12170d220000001511000000220b0805120218003500000000");
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(3, from);
        packet[packet.length - 6] = (byte) error;
        buffer.putInt(packet.length - 4, id);
        return packet;
    }

    private static byte[] hex(String value) { return HEX.parseHex(value); }

    private static void check(boolean outcome, String label) {
        if (!outcome) throw new AssertionError(label);
    }

    private static final class SimulatedRadio implements RadioProtocol.Link, RadioProtocol.Events {
        final List<byte[]> writes = new ArrayList<>();
        List<RadioProtocol.Destination> destinations = List.of();
        List<RadioProtocol.Node> nodes = List.of();
        final java.util.Map<Long, Integer> deliveries = new java.util.HashMap<>();
        RadioProtocol.Destination reply;
        int reads;
        boolean ready;
        String senderId;
        String author = "";
        String body = "";
        long receivedAt;
        String notice = "";
        String failure;

        @Override public void write(byte[] frame) { writes.add(frame.clone()); }
        @Override public void read() { reads++; }
        @Override public void ready() { ready = true; }
        @Override public void destinations(List<RadioProtocol.Destination> values) { destinations = values; }
        @Override public void nodes(List<RadioProtocol.Node> values) { nodes = values; }
        @Override public void delivery(long sendId, int state) { deliveries.put(sendId, state); }
        @Override public void message(RadioProtocol.Destination replyTo, String senderId, String author,
                String body, long timestampMillis) {
            this.reply = replyTo;
            this.senderId = senderId;
            this.author = author;
            this.body = body;
            this.receivedAt = timestampMillis;
        }
        @Override public void notice(String value) { notice = value; }
        @Override public void failed(String value) { failure = value; }
    }
}
