package com.limitedunderground.meshinbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** The messaging and first-time setup subset of Meshtastic's BLE PhoneAPI (ToRadio and FromRadio). */
final class MeshtasticProtocol implements RadioProtocol {
    private static final UUID SERVICE = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd");
    private static final UUID TO_RADIO = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7");
    private static final UUID FROM_RADIO = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002");
    private static final UUID FROM_NUM = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453");

    private static final long BROADCAST = 0xffffffffL;
    private static final int TEXT_MESSAGE = 1;
    private static final int NODE_INFO = 4;
    private static final int ROUTING = 5;
    private static final int ADMIN = 6;
    private static final int LORA_CONFIG_TYPE = 5;
    private static final int ADMIN_BAD_SESSION_KEY = 36;
    // Leave space for protobuf and public-key encryption overhead on the air.
    private static final int TEXT_LIMIT = 200;
    private static final byte[] EMPTY_PUBLIC_KEY = new byte[32];
    private static final String[] PRESET_NAMES = {"LongFast", "LongSlow", "VeryLongSlow", "MediumSlow",
            "MediumFast", "ShortSlow", "ShortFast", "LongModerate", "ShortTurbo", "LongTurbo",
            "LiteFast", "LiteSlow", "NarrowFast", "NarrowSlow", "TinyFast", "TinySlow", "MediumTurbo"};

    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, Channel> channels = new TreeMap<>();
    private final Map<Long, Peer> peers = new LinkedHashMap<>();
    private final Map<Long, Heard> heard = new LinkedHashMap<>();
    private List<Node> advertisedNodes = List.of();
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final Map<Long, Route> pending = new LinkedHashMap<>();
    private List<Destination> advertised = List.of();
    private final Set<Long> adminRequests = new HashSet<>();
    private final Map<Integer, MeshtasticSetup.RegionRule> regionRules = new HashMap<>();

    private enum SetupStep { IDLE, AWAITING_KEY, SENT }

    private Link link;
    private Events events;
    private long configurationNonce;
    private long ownNode;
    private boolean ready;
    private boolean reading;
    private boolean readAgain;
    private String presetName;
    private byte[] loraConfig;
    private byte[] ownUser = new byte[0];
    private byte[] sessionKey = new byte[0];
    private SetupStep setupStep = SetupStep.IDLE;
    private MeshtasticSetup.Request setupRequest;

    @Override public UUID serviceId() { return SERVICE; }
    @Override public UUID writeId() { return TO_RADIO; }
    @Override public UUID readId() { return FROM_RADIO; }
    @Override public UUID notifyId() { return FROM_NUM; }
    @Override public boolean framesInNotifications() { return false; }

    @Override
    public void open(Link link, Events events) {
        close();
        this.link = link;
        this.events = events;
        // A nonce outside the two reserved staged-handshake values requests the
        // full configuration and node database in a single pass.
        do {
            configurationNonce = Integer.toUnsignedLong(random.nextInt());
        } while (configurationNonce == 0 || configurationNonce == 69420 || configurationNonce == 69421);
        link.write(new ProtoWriter().varintField(3, configurationNonce).bytes());
        onAvailable();
    }

    @Override
    public void close() {
        link = null;
        events = null;
        configurationNonce = 0;
        ownNode = 0;
        ready = false;
        reading = false;
        readAgain = false;
        presetName = null;
        loraConfig = null;
        ownUser = new byte[0];
        sessionKey = new byte[0];
        setupStep = SetupStep.IDLE;
        setupRequest = null;
        adminRequests.clear();
        regionRules.clear();
        channels.clear();
        peers.clear();
        heard.clear();
        advertisedNodes = List.of();
        routes.clear();
        pending.clear();
        advertised = List.of();
    }

    @Override
    public void onAvailable() {
        if (link == null) return;
        if (reading) {
            // A wake may race an in-flight empty read. Recheck once after it.
            readAgain = true;
            return;
        }
        reading = true;
        link.read();
    }

    @Override
    public void onBytes(byte[] frame) {
        if (link == null || !reading) return;
        if (frame.length == 0) {
            reading = false;
            if (readAgain) {
                readAgain = false;
                onAvailable();
            }
            return;
        }
        try {
            receive(Proto.parse(frame));
        } catch (MalformedProtobuf ignored) {
            events.notice(Text.t("text_mt_unreadable", "Meshtastic returned an unreadable packet."));
        }
        if (link != null) link.read();
    }

    private void receive(Proto frame) throws MalformedProtobuf {
        if (frame.number(8) != 0) {
            fail(Text.t("text_mt_restarted", "The Meshtastic radio restarted."));
            return;
        }
        if (frame.contains(18)) {
            long lockdown = frame.message(18).number(1);
            if (lockdown != 3 && lockdown != 5) {
                fail(Text.t("text_mt_lockdown", "This Meshtastic radio requires a compatible authenticated client."));
            }
            return;
        }
        if (!ready && frame.contains(3)) {
            ownNode = frame.message(3).number(1);
        } else if (frame.contains(19)) {
            readRegionRules(frame.binary(19));
        } else if (frame.contains(4)) {
            Proto node = frame.message(4);
            long number = node.number(1);
            if (node.contains(2)) {
                if (number == ownNode) ownUser = node.binary(2);
                updatePeer(number, node.message(2), (int) node.number(7), node.number(11) != 0);
            }
            readNodeInfo(number, node);
        } else if (frame.contains(5)) {
            Proto configuration = frame.message(5);
            if (configuration.contains(6)) updateLora(configuration.binary(6));
        } else if (frame.contains(10)) {
            updateChannel(frame.message(10));
        } else if (!ready && frame.number(7) == configurationNonce) {
            if (!isNode(ownNode)) {
                fail(Text.t("text_mt_no_node", "The Meshtastic radio did not report a usable local node."));
                return;
            }
            ready = true;
            publishDestinations();
            publishNodes();
            if (link != null) events.ready();
        } else if (ready && frame.contains(2)) {
            receivePacket(frame.message(2));
        } else if (ready && frame.contains(11)) {
            Proto queue = frame.message(11);
            if (queue.number(1) != 0 && pending.remove(queue.number(4)) != null) {
                events.notice(Text.t("text_mt_queue_rejected", "The Meshtastic radio rejected a queued message."));
            }
        }
    }

    private void fail(String message) {
        Events callback = events;
        close();
        callback.failed(message);
    }

    private void updateChannel(Proto channel) throws MalformedProtobuf {
        long index = channel.number(1);
        long role = channel.number(3);
        if (index < 0 || index > 255) return;

        if ((role == 1 || role == 2) && channel.contains(2)) {
            String name = channel.message(2).string(3);
            channels.put((int) index, new Channel(name, role == 1));
        } else {
            channels.remove((int) index);
        }
        if (ready) publishDestinations();
    }

    private void updatePeer(long number, Proto user, int channel, boolean ignored) {
        if (!isNode(number)) return;
        String label = user.string(2);
        if (label.trim().isEmpty()) label = user.string(3);
        if (label.trim().isEmpty()) label = nodeLabel(number);
        byte[] publicKey = user.binary(8);
        if (publicKey.length != EMPTY_PUBLIC_KEY.length || Arrays.equals(publicKey, EMPTY_PUBLIC_KEY)) {
            publicKey = null;
        }
        peers.put(number, new Peer(label, channel, ignored || user.number(9) != 0,
                user.number(6) != 0, publicKey));
        if (ready) {
            publishDestinations();
            publishNodes();
        }
    }

    /** Last heard, signal and hops from the radio's node database (NodeInfo fields 4, 5 and 9). */
    private void readNodeInfo(long number, Proto node) {
        if (!isNode(number) || number == ownNode) return;
        long seconds = node.number(5);
        long now = System.currentTimeMillis();
        long lastHeard = seconds * 1000L;
        // The radio's clock can be unset; keep only times that make sense.
        if (seconds < 1_577_836_800L || lastHeard > now + 86_400_000L) lastHeard = 0;
        Float snr = node.contains(4) ? Float.intBitsToFloat((int) node.number(4)) : null;
        Integer hops = node.contains(9) ? (int) Math.min(node.number(9), 255) : null;
        heard.put(number, new Heard(lastHeard, snr, hops));
        if (ready) publishNodes();
    }

    /** Any packet from another node refreshes when it was heard, its signal and its hop count. */
    private void noteHeard(Proto packet) {
        long from = packet.number(1);
        if (!isNode(from) || from == ownNode || ownNode == 0) return;
        Heard previous = heard.get(from);
        Float snr = packet.contains(8) ? Float.valueOf(Float.intBitsToFloat((int) packet.number(8)))
                : previous == null ? null : previous.snr();
        long start = packet.number(15);
        long limit = packet.number(9);
        Integer hops = start > 0 && start >= limit ? Integer.valueOf((int) (start - limit))
                : previous == null ? null : previous.hops();
        heard.put(from, new Heard(System.currentTimeMillis(), snr, hops));
        if (ready) publishNodes();
    }

    private void publishNodes() {
        Set<Long> numbers = new java.util.LinkedHashSet<>(peers.keySet());
        numbers.addAll(heard.keySet());
        List<Node> nodes = new ArrayList<>(numbers.size());
        for (long number : numbers) {
            if (number == ownNode || !isNode(number)) continue;
            Peer peer = peers.get(number);
            Heard seen = heard.get(number);
            nodes.add(new Node(nodeId(number), peer == null ? nodeLabel(number) : peer.name(), nodeLabel(number),
                    Node.MESHTASTIC, seen == null ? 0 : seen.lastHeardMillis(),
                    seen == null ? null : seen.snr(), seen == null ? null : seen.hops()));
        }
        if (!advertisedNodes.equals(nodes)) {
            advertisedNodes = List.copyOf(nodes);
            events.nodes(advertisedNodes);
        }
    }

    private void publishDestinations() {
        routes.clear();
        channels.forEach((index, channel) -> {
            String name = channel.name();
            if (name.trim().isEmpty()) {
                name = channel.primary() && presetName != null ? presetName : Text.t("text_channel_number", "Channel %1$d", index);
            }
            String id = channelId(index);
            Destination destination = new Destination(id, name, true);
            routes.put(id, new Route(destination, BROADCAST, index, null));
        });
        Peer ownPeer = peers.get(ownNode);
        boolean hasPki = ownPeer != null && ownPeer.publicKey() != null && !ownPeer.licensed();
        peers.forEach((number, peer) -> {
            if (number == ownNode || peer.blocked()) return;
            byte[] publicKey = hasPki ? peer.publicKey() : null;
            if (hasPki && publicKey == null) return;
            if (publicKey == null && !channels.containsKey(peer.channel())) return;

            String id = peerId(number, peer);
            String label = peer.name().equals(nodeLabel(number)) ? peer.name()
                    : peer.name() + " (" + nodeLabel(number) + ")";
            Destination destination = new Destination(id, label, false);
            routes.put(id, new Route(destination, number, peer.channel(), publicKey));
        });
        List<Destination> destinations = new ArrayList<>(routes.size());
        routes.forEach((id, route) -> destinations.add(route.destination()));
        if (!advertised.equals(destinations)) {
            advertised = List.copyOf(destinations);
            events.destinations(advertised);
        }
    }

    private void receivePacket(Proto packet) throws MalformedProtobuf {
        noteHeard(packet);
        if (!packet.contains(4)) return;
        Proto data = packet.message(4);
        long port = data.number(1);
        long from = packet.number(1);
        long to = packet.number(2);
        int channel = (int) packet.number(3);
        if (port == ROUTING && (to == ownNode || to == 0)) {
            receiveRouting(data, from, packet.number(23));
            return;
        }
        if (port == ADMIN) {
            if (from == ownNode || from == 0) receiveAdmin(data);
            return;
        }
        if (!isNode(from) || from == ownNode || (to != BROADCAST && to != ownNode)) return;

        if (port == NODE_INFO) {
            receivePeerAdvertisement(from, channel, Proto.parse(data.binary(2)));
            return;
        }
        // Emoji reactions are marked separately despite sharing the text port.
        if (port != TEXT_MESSAGE || data.number(8) != 0) return;

        String message = data.string(2);
        if (message.trim().isEmpty()) return;
        Peer peer = peers.get(from);
        String author = peer == null ? nodeLabel(from) : peer.name();
        Route reply = routes.get(to == BROADCAST ? channelId(channel) : peerId(from, peer));
        long seconds = packet.number(7);
        long receivedAt = seconds == 0 ? System.currentTimeMillis() : seconds * 1000L;
        events.message(reply == null ? null : reply.destination(), nodeId(from), author, message, receivedAt);
    }

    private void receivePeerAdvertisement(long number, int channel, Proto user) {
        Peer ownPeer = peers.get(ownNode);
        Peer existing = peers.get(number);
        byte[] advertisedKey = user.binary(8);
        // Match the radio's identity checks before using a mesh advertisement
        // to refresh a route. In particular, do not replace a known peer key.
        if (ownPeer != null && (ownPeer.licensed() != (user.number(6) != 0)
                || Arrays.equals(ownPeer.publicKey(), advertisedKey))) return;
        if (existing != null && existing.publicKey() != null
                && !Arrays.equals(existing.publicKey(), advertisedKey)) return;
        updatePeer(number, user, channel, existing != null && existing.blocked());
    }

    private void receiveRouting(Proto data, long from, long proof) throws MalformedProtobuf {
        long requestId = data.number(6);
        if (adminRequests.contains(requestId)) {
            Proto routing = Proto.parse(data.binary(2));
            if (!routing.contains(3)) return;
            long error = routing.number(3);
            // A successful acknowledgement keeps the id: the reply to a request may follow it.
            if (error == 0) return;
            adminRequests.remove(requestId);
            if (error == ADMIN_BAD_SESSION_KEY) {
                failSetup(Text.t("text_setup_key_expired", "The radio refused the settings because its session key expired. Try again."));
            } else {
                failSetup(Text.t("text_setup_refused_code", "The radio refused the settings (routing code %1$d).", error));
            }
            return;
        }
        Route route = pending.get(requestId);
        if (route == null) return;
        Proto routing = Proto.parse(data.binary(2));
        if (!routing.contains(3)) return;
        long error = routing.number(3);
        if (error == 0 && route.number() != BROADCAST && from != route.number()) return;
        pending.remove(requestId);
        if (error != 0) {
            events.notice(Text.t("text_mt_send_failed", "Meshtastic reported a send failure (routing code %1$d).", error));
        } else if (route.number() == BROADCAST) {
            events.notice(Text.t("text_mt_relay_ack", "Meshtastic reported a channel relay acknowledgement."));
        } else if (proof == 1) {
            events.notice(Text.t("text_mt_verified_ack", "Meshtastic verified the recipient's acknowledgement."));
        } else {
            events.notice(Text.t("text_mt_ack", "Meshtastic reported an acknowledgement."));
        }
    }

    /** The radio's current setup values, or null before the handshake has delivered them. */
    MeshtasticSetup.Current setupState() {
        if (!ready || loraConfig == null) return null;
        try {
            Proto lora = Proto.parse(loraConfig);
            Proto user = Proto.parse(ownUser);
            return new MeshtasticSetup.Current((int) lora.number(7), lora.number(1) != 0, (int) lora.number(2),
                    user.string(2), user.string(3), lora.number(9) != 0, Map.copyOf(regionRules));
        } catch (MalformedProtobuf error) {
            return null;
        }
    }

    /**
     * Starts writing region, preset and names. The radio is asked for its LoRa settings first,
     * so the change is built on its latest values and carries the session key it returns.
     */
    void applySetup(MeshtasticSetup.Request request) {
        if (!ready || link == null) throw new IllegalStateException(Text.t("text_mt_not_connected", "Meshtastic is not connected."));
        MeshtasticSetup.Current current = setupState();
        if (current == null) throw new IllegalStateException(Text.t("text_setup_not_reported", "The radio has not reported its settings yet."));
        String problem = MeshtasticSetup.validate(request, current.regionRules());
        if (problem != null) throw new IllegalArgumentException(problem);
        if (setupStep == SetupStep.AWAITING_KEY) throw new IllegalStateException(Text.t("text_setup_running", "Radio setup is already running."));
        setupRequest = request;
        setupStep = SetupStep.AWAITING_KEY;
        sendAdmin(new ProtoWriter().varintField(5, LORA_CONFIG_TYPE).bytes(), true);
    }

    /** Asks the radio for its LoRa settings and owner again, to confirm a change. */
    void refreshSetup() {
        if (!ready || link == null) return;
        sendAdmin(new ProtoWriter().varintField(5, LORA_CONFIG_TYPE).bytes(), true);
        sendAdmin(new ProtoWriter().varintField(3, 1).bytes(), true);
    }

    private void receiveAdmin(Proto data) throws MalformedProtobuf {
        long requestId = data.number(6);
        if (!adminRequests.remove(requestId)) return;
        Proto admin = Proto.parse(data.binary(2));
        if (admin.contains(101)) sessionKey = admin.binary(101);
        boolean loraReply = false;
        if (admin.contains(6)) {
            Proto configuration = admin.message(6);
            if (configuration.contains(6)) {
                updateLora(configuration.binary(6));
                loraReply = true;
            }
        }
        if (admin.contains(4)) {
            ownUser = admin.binary(4);
            if (events != null) events.setupChanged();
        }
        if (loraReply && setupStep == SetupStep.AWAITING_KEY) sendSetupChanges();
    }

    private void sendSetupChanges() {
        MeshtasticSetup.Request request = setupRequest;
        MeshtasticSetup.Current current = setupState();
        if (request == null || current == null) {
            failSetup(Text.t("text_setup_no_settings", "The radio did not report its settings."));
            return;
        }
        byte[] lora = MeshtasticSetup.withoutFields(loraConfig,
                request.preset() == null ? Set.of(7) : Set.of(1, 2, 7));
        byte[] user = MeshtasticSetup.withoutFields(ownUser, Set.of(2, 3));
        if (lora == null || user == null) {
            failSetup(Text.t("text_setup_cannot_edit", "The radio reported settings MeshParty cannot edit safely."));
            return;
        }
        ProtoWriter newLora = new ProtoWriter().raw(lora).varintField(7, request.region());
        if (request.preset() != null) newLora.varintField(1, 1).varintField(2, request.preset());
        String longName = request.longName().trim();
        String shortName = request.shortName().trim();
        boolean namesChanged = !current.longName().equals(longName) || !current.shortName().equals(shortName);
        boolean loraChanged = current.region() != request.region() || (request.preset() != null
                && (!current.usePreset() || current.preset() != request.preset()));
        try {
            sendAdmin(new ProtoWriter().varintField(64, 1).bytes(), false);
            if (namesChanged) {
                byte[] owner = new ProtoWriter().raw(user)
                        .binaryField(2, longName.getBytes(StandardCharsets.UTF_8))
                        .binaryField(3, shortName.getBytes(StandardCharsets.UTF_8)).bytes();
                sendAdmin(new ProtoWriter().binaryField(32, owner).bytes(), false);
            }
            if (loraChanged) {
                byte[] config = new ProtoWriter().binaryField(6, newLora.bytes()).bytes();
                sendAdmin(new ProtoWriter().binaryField(34, config).bytes(), false);
            }
            sendAdmin(new ProtoWriter().varintField(65, 1).bytes(), false);
        } catch (IllegalArgumentException | IllegalStateException error) {
            failSetup(error.getMessage() == null ? Text.t("text_setup_send_failed", "The settings could not be sent.") : error.getMessage());
            return;
        }
        setupStep = SetupStep.SENT;
        setupRequest = null;
        if (events != null) events.setupApplied();
    }

    private void sendAdmin(byte[] admin, boolean wantResponse) {
        ProtoWriter message = new ProtoWriter().raw(admin);
        if (sessionKey.length > 0) message.binaryField(101, sessionKey);
        ProtoWriter data = new ProtoWriter().varintField(1, ADMIN).binaryField(2, message.bytes());
        if (wantResponse) data.varintField(3, 1);
        long id;
        do {
            id = Integer.toUnsignedLong(random.nextInt());
        } while (id == 0 || pending.containsKey(id) || adminRequests.contains(id));
        ProtoWriter packet = new ProtoWriter().fixed32Field(2, ownNode).binaryField(4, data.bytes())
                .fixed32Field(6, id).varintField(10, 1);
        link.write(new ProtoWriter().binaryField(1, packet.bytes()).bytes());
        if (adminRequests.size() >= 32) adminRequests.remove(adminRequests.iterator().next());
        adminRequests.add(id);
    }

    private void failSetup(String reason) {
        setupStep = SetupStep.IDLE;
        setupRequest = null;
        if (events != null) events.setupFailed(reason);
    }

    private void updateLora(byte[] lora) throws MalformedProtobuf {
        Proto parsed = Proto.parse(lora);
        loraConfig = lora;
        presetName = parsed.number(1) == 0 ? Text.t("text_mt_custom", "Custom") : presetLabel(parsed.number(2));
        if (ready) {
            publishDestinations();
            if (events != null) events.setupChanged();
        }
    }

    private void readRegionRules(byte[] map) throws MalformedProtobuf {
        List<MeshtasticSetup.RegionRule> groups = new ArrayList<>();
        for (byte[] group : Proto.repeatedBinary(map, 1)) {
            Proto parsed = Proto.parse(group);
            List<Integer> presets = new ArrayList<>();
            for (long preset : Proto.repeatedNumbers(group, 1)) presets.add((int) preset);
            groups.add(new MeshtasticSetup.RegionRule(List.copyOf(presets), (int) parsed.number(2),
                    parsed.number(3) != 0));
        }
        regionRules.clear();
        for (byte[] entry : Proto.repeatedBinary(map, 2)) {
            Proto parsed = Proto.parse(entry);
            long index = parsed.number(2);
            if (index >= 0 && index < groups.size()) regionRules.put((int) parsed.number(1), groups.get((int) index));
        }
    }

    @Override
    public void send(Destination destination, String text) {
        if (!ready || link == null) throw new IllegalStateException(Text.t("text_mt_not_connected", "Meshtastic is not connected."));
        Route route = destination == null ? null : routes.get(destination.id());
        if (route == null || !route.destination().equals(destination)) {
            throw new IllegalArgumentException(Text.t("text_mt_choose_destination", "Choose an available Meshtastic destination."));
        }
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        if (text.trim().isEmpty() || content.length > TEXT_LIMIT) {
            throw new IllegalArgumentException(Text.t("text_message_limit", "Enter a message of up to %1$d UTF-8 bytes.", TEXT_LIMIT));
        }

        long id;
        do {
            id = Integer.toUnsignedLong(random.nextInt());
        } while (id == 0 || pending.containsKey(id));
        byte[] data = new ProtoWriter().varintField(1, TEXT_MESSAGE).binaryField(2, content).bytes();
        ProtoWriter packet = new ProtoWriter().fixed32Field(2, route.number())
                .varintField(3, route.channel()).binaryField(4, data)
                .fixed32Field(6, id).varintField(10, 1);
        if (route.publicKey() != null) {
            packet.binaryField(16, route.publicKey()).varintField(17, 1);
        }
        link.write(new ProtoWriter().binaryField(1, packet.bytes()).bytes());
        if (pending.size() >= 64) pending.remove(pending.keySet().iterator().next());
        pending.put(id, route);
    }

    private static boolean isNode(long number) {
        return number > 3 && number < BROADCAST;
    }

    private static String nodeLabel(long number) {
        return String.format(Locale.ROOT, "!%08x", number);
    }

    private static String channelId(int channel) { return "channel:" + channel; }

    private static String nodeId(long node) { return "node:" + nodeLabel(node).substring(1); }

    private static String peerId(long node, Peer peer) {
        String id = nodeId(node);
        if (peer == null || peer.publicKey() == null) return id;
        return id + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(peer.publicKey());
    }

    private static String presetLabel(long preset) {
        return preset >= 0 && preset < PRESET_NAMES.length ? PRESET_NAMES[(int) preset] : null;
    }

    private record Channel(String name, boolean primary) {}
    private record Heard(long lastHeardMillis, Float snr, Integer hops) {}
    private record Peer(String name, int channel, boolean blocked, boolean licensed, byte[] publicKey) {}
    private record Route(Destination destination, long number, int channel, byte[] publicKey) {}

    // Standard protobuf wire types used by mesh.proto. Unknown fields can be
    // skipped and no upstream generated code is needed for the messaging subset.
    private static final class Proto {
        private static final byte[] EMPTY = new byte[0];
        private final Map<Integer, Object> fields = new HashMap<>();

        static Proto parse(byte[] bytes) throws MalformedProtobuf {
            Cursor input = new Cursor(bytes);
            Proto proto = new Proto();
            while (input.position < bytes.length) {
                long tag = input.varint();
                long fieldNumber = tag >>> 3;
                if (fieldNumber == 0 || fieldNumber > 0x1fffffff) throw new MalformedProtobuf();
                int field = (int) fieldNumber;
                switch ((int) (tag & 7)) {
                    case 0 -> proto.fields.put(field, input.varint());
                    case 1 -> input.advance(8);
                    case 2 -> {
                        long size = input.varint();
                        if (size < 0 || size > bytes.length - input.position) throw new MalformedProtobuf();
                        int start = input.position;
                        input.advance((int) size);
                        proto.fields.put(field, Arrays.copyOfRange(bytes, start, input.position));
                    }
                    case 5 -> {
                        int start = input.position;
                        input.advance(4);
                        long value = 0;
                        for (int i = 0; i < 4; i++) value |= (bytes[start + i] & 0xffL) << (i * 8);
                        proto.fields.put(field, value);
                    }
                    default -> throw new MalformedProtobuf();
                }
            }
            return proto;
        }

        boolean contains(int field) { return fields.containsKey(field); }
        long number(int field) { return fields.get(field) instanceof Long value ? value : 0; }
        byte[] binary(int field) { return fields.get(field) instanceof byte[] bytes ? bytes : EMPTY; }
        String string(int field) { return new String(binary(field), StandardCharsets.UTF_8); }
        Proto message(int field) throws MalformedProtobuf { return parse(binary(field)); }

        /** Every length-delimited value of a repeated field, in order. */
        static List<byte[]> repeatedBinary(byte[] bytes, int wanted) throws MalformedProtobuf {
            List<byte[]> values = new ArrayList<>();
            scan(bytes, wanted, values, null);
            return values;
        }

        /** Every value of a repeated varint field, packed or not. */
        static List<Long> repeatedNumbers(byte[] bytes, int wanted) throws MalformedProtobuf {
            List<byte[]> packed = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            scan(bytes, wanted, packed, values);
            for (byte[] run : packed) {
                Cursor cursor = new Cursor(run);
                while (cursor.position < run.length) values.add(cursor.varint());
            }
            return values;
        }

        private static void scan(byte[] bytes, int wanted, List<byte[]> binary, List<Long> numbers)
                throws MalformedProtobuf {
            Cursor input = new Cursor(bytes);
            while (input.position < bytes.length) {
                long tag = input.varint();
                int field = (int) (tag >>> 3);
                switch ((int) (tag & 7)) {
                    case 0 -> {
                        long value = input.varint();
                        if (field == wanted && numbers != null) numbers.add(value);
                    }
                    case 1 -> input.advance(8);
                    case 2 -> {
                        long size = input.varint();
                        if (size < 0 || size > bytes.length - input.position) throw new MalformedProtobuf();
                        int start = input.position;
                        input.advance((int) size);
                        if (field == wanted) binary.add(Arrays.copyOfRange(bytes, start, input.position));
                    }
                    case 5 -> input.advance(4);
                    default -> throw new MalformedProtobuf();
                }
            }
        }
    }


    private static final class Cursor {
        private final byte[] bytes;
        private int position;

        Cursor(byte[] bytes) { this.bytes = bytes; }

        void advance(int count) throws MalformedProtobuf {
            if (count > bytes.length - position) throw new MalformedProtobuf();
            position += count;
        }

        long varint() throws MalformedProtobuf {
            long value = 0;
            for (int shift = 0; shift < 64 && position < bytes.length; shift += 7) {
                int next = bytes[position++] & 0xff;
                if (shift == 63 && next > 1) throw new MalformedProtobuf();
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            throw new MalformedProtobuf();
        }
    }

    private static final class ProtoWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        ProtoWriter varintField(int field, long value) {
            writeVarint(field << 3);
            writeVarint(value);
            return this;
        }

        ProtoWriter binaryField(int field, byte[] value) {
            writeVarint((field << 3) | 2);
            writeVarint(value.length);
            output.write(value, 0, value.length);
            return this;
        }

        ProtoWriter fixed32Field(int field, long value) {
            writeVarint((field << 3) | 5);
            for (int i = 0; i < 4; i++) output.write((int) (value >>> (i * 8)) & 0xff);
            return this;
        }

        ProtoWriter raw(byte[] value) {
            output.write(value, 0, value.length);
            return this;
        }

        byte[] bytes() { return output.toByteArray(); }

        private void writeVarint(long value) {
            while ((value & ~0x7fL) != 0) {
                output.write((int) (value & 0x7f) | 0x80);
                value >>>= 7;
            }
            output.write((int) value);
        }
    }

    private static final class MalformedProtobuf extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
