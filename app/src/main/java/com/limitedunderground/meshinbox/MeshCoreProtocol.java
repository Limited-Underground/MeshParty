package com.limitedunderground.meshinbox;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MeshCore Companion Protocol text messaging over the Nordic UART BLE service. */
public final class MeshCoreProtocol implements RadioProtocol {
    static final String UNKNOWN_CHANNEL_SENDER = "MeshCore sender";
    private static final UUID SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    private static final byte APP_START = 1;
    private static final byte SEND_DIRECT = 2;
    private static final byte SEND_CHANNEL = 3;
    private static final byte GET_CONTACTS = 4;
    private static final byte SYNC_NEXT_MESSAGE = 10;
    private static final byte DEVICE_QUERY = 22;
    private static final byte GET_CHANNEL = 31;
    private static final byte PROTOCOL_VERSION = 3;

    private static final int OK = 0;
    private static final int ERROR = 1;
    private static final int CONTACTS_START = 2;
    private static final int CONTACT = 3;
    private static final int CONTACTS_END = 4;
    private static final int SELF_INFO = 5;
    private static final int SENT = 6;
    private static final int DIRECT_MESSAGE = 7;
    private static final int CHANNEL_MESSAGE = 8;
    private static final int NO_MORE_MESSAGES = 10;
    private static final int DEVICE_INFO = 13;
    private static final int DIRECT_MESSAGE_V3 = 16;
    private static final int CHANNEL_MESSAGE_V3 = 17;
    private static final int CHANNEL_INFO = 18;
    private static final int CHANNEL_DATA = 27;
    private static final int SEND_CONFIRMED = 0x82;
    private static final int MESSAGES_WAITING = 0x83;
    private static final int NEW_CONTACT = 0x8A;
    private static final int CONTACT_DELETED = 0x8F;
    private static final int NOT_FOUND = 2;
    private static final int CHAT_CONTACT = 1;

    private static final int PUBLIC_KEY_BYTES = 32;
    private static final int KEY_PREFIX_BYTES = 6;
    private static final int ADVERT_NAME_BYTES = 32;
    private static final int SELF_NAME_OFFSET = 58;
    private static final int CONTACT_NAME_OFFSET = 1 + PUBLIC_KEY_BYTES + 3 + 64;
    private static final int MAX_TEXT_BYTES = 160;
    private static final long COMMAND_TIMEOUT_MS = 8_000;
    private static final long POLL_INTERVAL_MS = 30_000;
    private static final long MAX_ACK_WAIT_MS = 120_000;

    private enum Operation { START, DEVICE, CONTACTS, CHANNEL, DRAIN, SEND_DIRECT, SEND_CHANNEL }

    private record Contact(Destination destination, String prefix, byte[] wirePrefix, boolean chat) {}
    private record Outgoing(Destination destination, byte[] text) {}

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable deadline = this::commandTimedOut;
    private final Runnable poll = this::pollMessages;
    private final Map<Integer, Destination> channels = new LinkedHashMap<>();
    private final Map<String, Contact> contacts = new LinkedHashMap<>();
    private final Map<Integer, Long> expectedAcks = new HashMap<>();
    private Link link;
    private Events events;
    private Operation operation;
    private Outgoing queuedSend;
    private boolean ready;
    private boolean waitingMessages;
    private boolean contactsStarted;
    private int nextChannel;
    private int channelCount;
    private int maxChannelTextBytes;
    private long lastSendTimestamp;

    @Override public UUID serviceId() { return SERVICE; }
    @Override public UUID writeId() { return WRITE; }
    @Override public UUID readId() { return NOTIFY; }
    @Override public UUID notifyId() { return NOTIFY; }
    @Override public boolean framesInNotifications() { return true; }

    @Override
    public void open(Link link, Events events) {
        close();
        this.link = link;
        this.events = events;
        byte[] appName = "MeshParty".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[8 + appName.length];
        frame[0] = APP_START;
        System.arraycopy(appName, 0, frame, 8, appName.length);
        command(Operation.START, frame);
    }

    @Override
    public void close() {
        handler.removeCallbacks(deadline);
        handler.removeCallbacks(poll);
        link = null;
        events = null;
        operation = null;
        queuedSend = null;
        ready = false;
        waitingMessages = false;
        contactsStarted = false;
        nextChannel = 0;
        channelCount = 0;
        maxChannelTextBytes = MAX_TEXT_BYTES - ADVERT_NAME_BYTES - 2;
        channels.clear();
        contacts.clear();
        expectedAcks.clear();
    }

    @Override
    public void onAvailable() {
        // Companion responses are the notification payload. No characteristic read is needed.
    }

    @Override
    public void onBytes(byte[] frame) {
        if (link == null || frame.length == 0) return;
        int code = unsigned(frame[0]);
        switch (code) {
            case MESSAGES_WAITING -> {
                waitingMessages = true;
                runWork();
            }
            case SEND_CONFIRMED -> acknowledge(frame);
            case NEW_CONTACT -> {
                if (readContact(frame) && ready) publishDestinations();
            }
            case CONTACT_DELETED -> {
                if (frame.length >= 1 + PUBLIC_KEY_BYTES) {
                    String key = hex(frame, 1, PUBLIC_KEY_BYTES);
                    if (contacts.remove(key) != null && ready) publishDestinations();
                }
            }
            default -> acceptResponse(code, frame);
        }
    }

    @Override
    public void send(Destination destination, String text) {
        if (!ready) throw new IllegalStateException("The MeshCore radio is still connecting.");
        if (text == null || text.trim().isEmpty() || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Enter a text message without null characters.");
        }
        if (destination == null) throw new IllegalArgumentException("Choose a MeshCore destination.");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int limit = destination.channel() ? maxChannelTextBytes : MAX_TEXT_BYTES;
        if (bytes.length > limit) {
            throw new IllegalArgumentException("MeshCore allows " + limit + " UTF-8 bytes for this destination.");
        }
        validateDestination(destination);
        if (queuedSend != null || (operation != null && operation != Operation.DRAIN)) {
            throw new IllegalStateException("MeshCore is still processing a message. Try again shortly.");
        }
        queuedSend = new Outgoing(destination, bytes);
        runWork();
    }

    private void acceptResponse(int code, byte[] frame) {
        if (operation == null || code >= 0x80) return;
        if (code == ERROR) {
            commandRejected(frame.length >= 2 ? unsigned(frame[1]) : 0);
            return;
        }
        switch (operation) {
            case START -> {
                if (code != SELF_INFO || frame.length < 4 + PUBLIC_KEY_BYTES) return;
                if (frame.length >= SELF_NAME_OFFSET) {
                    int nameLength = nulTerminatedLength(frame, SELF_NAME_OFFSET, ADVERT_NAME_BYTES);
                    maxChannelTextBytes = MAX_TEXT_BYTES - nameLength - 2;
                }
                finishCommand();
                command(Operation.DEVICE, new byte[] { DEVICE_QUERY, PROTOCOL_VERSION });
            }
            case DEVICE -> {
                if (code != DEVICE_INFO) return;
                if (frame.length < 4 || unsigned(frame[1]) < PROTOCOL_VERSION) {
                    fail("MeshCore Companion protocol version 3 or later is required.");
                    return;
                }
                channelCount = unsigned(frame[3]);
                finishCommand();
                command(Operation.CONTACTS, new byte[] { GET_CONTACTS });
            }
            case CONTACTS -> {
                if (code == CONTACTS_START && frame.length >= 5) {
                    contactsStarted = true;
                    refreshDeadline();
                } else if (contactsStarted && code == CONTACT && readContact(frame)) {
                    refreshDeadline();
                } else if (contactsStarted && code == CONTACTS_END && frame.length >= 5) {
                    finishCommand();
                    queryNextChannel();
                }
            }
            case CHANNEL -> {
                if (code != CHANNEL_INFO || frame.length < 50 || unsigned(frame[1]) != nextChannel) return;
                String name = readString(frame, 2, ADVERT_NAME_BYTES);
                if (!name.trim().isEmpty()) {
                    channels.put(nextChannel, new Destination("channel:" + nextChannel, name, true));
                }
                finishCommand();
                nextChannel++;
                queryNextChannel();
            }
            case DRAIN -> receiveQueuedMessage(code, frame);
            case SEND_DIRECT -> {
                if (code != SENT || frame.length < 10) return;
                int ack = readInt(frame, 2);
                long suggestedTimeout = Integer.toUnsignedLong(readInt(frame, 6));
                removeExpiredAcks();
                if (ack != 0) {
                    long wait = Math.min(MAX_ACK_WAIT_MS, Math.max(1_000, suggestedTimeout));
                    expectedAcks.put(ack, SystemClock.elapsedRealtime() + wait);
                }
                finishCommand();
                events.notice("MeshCore radio accepted the direct message.");
                runWork();
            }
            case SEND_CHANNEL -> {
                if (code != OK) return;
                finishCommand();
                events.notice("MeshCore radio accepted the channel message.");
                runWork();
            }
        }
    }

    private void queryNextChannel() {
        if (nextChannel < channelCount) {
            command(Operation.CHANNEL, new byte[] { GET_CHANNEL, (byte) nextChannel });
            return;
        }
        ready = true;
        waitingMessages = true;
        publishDestinations();
        if (events != null) events.ready();
        runWork();
        if (ready) handler.postDelayed(poll, POLL_INTERVAL_MS);
    }

    private void pollMessages() {
        if (!ready) return;
        removeExpiredAcks();
        waitingMessages = true;
        runWork();
        if (ready) handler.postDelayed(poll, POLL_INTERVAL_MS);
    }

    private void runWork() {
        if (!ready || operation != null) return;
        if (queuedSend != null) {
            Outgoing outgoing = queuedSend;
            queuedSend = null;
            if (destinationIsCurrent(outgoing.destination())) {
                sendMessage(outgoing);
                return;
            }
            events.notice("MeshCore destination changed before the message could be sent.");
        }
        if (waitingMessages && ready && operation == null) {
            waitingMessages = false;
            command(Operation.DRAIN, new byte[] { SYNC_NEXT_MESSAGE });
        }
    }

    private void sendMessage(Outgoing outgoing) {
        lastSendTimestamp = Math.max(System.currentTimeMillis() / 1_000, lastSendTimestamp + 1);
        Destination destination = outgoing.destination();
        if (destination.channel()) {
            int slot = Integer.parseInt(destination.id().substring("channel:".length()));
            byte[] frame = ByteBuffer.allocate(7 + outgoing.text().length).order(ByteOrder.LITTLE_ENDIAN)
                    .put(SEND_CHANNEL).put((byte) 0).put((byte) slot)
                    .putInt((int) lastSendTimestamp).put(outgoing.text()).array();
            command(Operation.SEND_CHANNEL, frame);
        } else {
            Contact contact = contacts.get(destination.id());
            byte[] frame = ByteBuffer.allocate(7 + KEY_PREFIX_BYTES + outgoing.text().length)
                    .order(ByteOrder.LITTLE_ENDIAN).put(SEND_DIRECT).put((byte) 0).put((byte) 0)
                    .putInt((int) lastSendTimestamp).put(contact.wirePrefix()).put(outgoing.text()).array();
            command(Operation.SEND_DIRECT, frame);
        }
    }

    private void receiveQueuedMessage(int code, byte[] frame) {
        if (code == NO_MORE_MESSAGES) {
            finishCommand();
            runWork();
            return;
        }
        if (code != DIRECT_MESSAGE && code != DIRECT_MESSAGE_V3
                && code != CHANNEL_MESSAGE && code != CHANNEL_MESSAGE_V3 && code != CHANNEL_DATA) return;
        finishCommand();
        waitingMessages = true;
        switch (code) {
            case DIRECT_MESSAGE -> receiveDirect(frame, 1);
            case DIRECT_MESSAGE_V3 -> receiveDirect(frame, 4);
            case CHANNEL_MESSAGE -> receiveChannel(frame, 1);
            case CHANNEL_MESSAGE_V3 -> receiveChannel(frame, 4);
            case CHANNEL_DATA -> { /* Drain binary datagrams without presenting them as text. */ }
        }
        runWork();
    }

    private void receiveDirect(byte[] frame, int offset) {
        int textTypeOffset = offset + KEY_PREFIX_BYTES + 1;
        int timestampOffset = textTypeOffset + 1;
        int textOffset = timestampOffset + 4;
        if (frame.length <= textOffset) return;
        int textType = unsigned(frame[textTypeOffset]);
        if (textType == 2) textOffset += 4; // Signed text has a four-byte prefix.
        else if (textType != 0) return;
        if (frame.length <= textOffset) return;
        String body = readString(frame, textOffset, frame.length - textOffset);
        if (body.isEmpty()) return;
        String prefix = hex(frame, offset, KEY_PREFIX_BYTES);
        Contact contact = uniqueContact(prefix);
        Destination replyTo = contact != null && contact.chat() ? contact.destination() : null;
        String senderId = contact == null ? null : contact.destination().id();
        long time = Integer.toUnsignedLong(readInt(frame, timestampOffset)) * 1_000;
        events.message(replyTo, senderId, replyTo == null ? "Contact " + prefix : replyTo.label(), body, time);
    }

    private void receiveChannel(byte[] frame, int offset) {
        int timestampOffset = offset + 3;
        int textOffset = timestampOffset + 4;
        if (frame.length <= textOffset || frame[offset + 2] != 0) return;
        int slot = unsigned(frame[offset]);
        Destination replyTo = channels.get(slot);
        String text = readString(frame, textOffset, frame.length - textOffset);
        if (text.isEmpty()) return;
        // Firmware prepends the sender's advertised name and a colon to channel text.
        // Channel names are display labels, as MeshCore group frames carry no sender key.
        int separator = text.indexOf(": ");
        String senderName = separator > 0 ? text.substring(0, separator) : "";
        String author = senderName.trim().isEmpty() ? UNKNOWN_CHANNEL_SENDER : senderName;
        String body = separator > 0 ? text.substring(separator + 2) : text;
        long time = Integer.toUnsignedLong(readInt(frame, timestampOffset)) * 1_000;
        events.message(replyTo, null, author, body, time);
    }

    private boolean readContact(byte[] frame) {
        if (frame.length < CONTACT_NAME_OFFSET + ADVERT_NAME_BYTES) return false;
        String key = hex(frame, 1, PUBLIC_KEY_BYTES);
        String prefix = hex(frame, 1, KEY_PREFIX_BYTES);
        String label = readString(frame, CONTACT_NAME_OFFSET, ADVERT_NAME_BYTES);
        boolean chat = unsigned(frame[1 + PUBLIC_KEY_BYTES]) == CHAT_CONTACT;
        Destination destination = new Destination(key, label.trim().isEmpty() ? "Contact " + prefix : label, false);
        byte[] wirePrefix = new byte[KEY_PREFIX_BYTES];
        System.arraycopy(frame, 1, wirePrefix, 0, KEY_PREFIX_BYTES);
        contacts.put(key, new Contact(destination, prefix, wirePrefix, chat));
        return true;
    }

    private void publishDestinations() {
        List<Destination> items = new ArrayList<>(channels.values());
        for (Contact contact : contacts.values()) {
            if (contact.chat() && uniqueContact(contact.prefix()) == contact) items.add(contact.destination());
        }
        events.destinations(List.copyOf(items));
    }

    private Contact uniqueContact(String prefix) {
        Contact match = null;
        for (Contact contact : contacts.values()) {
            if (!contact.prefix().equals(prefix)) continue;
            if (match != null) return null;
            match = contact;
        }
        return match;
    }

    private void validateDestination(Destination destination) {
        if (!destinationIsCurrent(destination)) {
            throw new IllegalArgumentException("Choose an available MeshCore channel or contact.");
        }
    }

    private boolean destinationIsCurrent(Destination destination) {
        if (destination.channel()) return channels.containsValue(destination);
        Contact contact = contacts.get(destination.id());
        return contact != null && contact.chat() && contact.destination().equals(destination)
                && uniqueContact(contact.prefix()) == contact;
    }

    private void acknowledge(byte[] frame) {
        if (frame.length < 9) return;
        removeExpiredAcks();
        if (expectedAcks.remove(readInt(frame, 1)) != null) {
            events.notice("The MeshCore recipient acknowledged a direct message.");
        }
    }

    private void removeExpiredAcks() {
        long now = SystemClock.elapsedRealtime();
        Iterator<Long> expiry = expectedAcks.values().iterator();
        while (expiry.hasNext()) {
            if (expiry.next() < now) expiry.remove();
        }
    }

    private void commandRejected(int error) {
        if (operation == Operation.CHANNEL && error == NOT_FOUND) {
            finishCommand();
            nextChannel++;
            queryNextChannel();
        } else if (operation == Operation.SEND_DIRECT || operation == Operation.SEND_CHANNEL) {
            finishCommand();
            events.notice("MeshCore radio rejected the message (error " + error + ").");
            runWork();
        } else {
            fail("MeshCore radio rejected the " + operationLabel() + " command (error " + error + ").");
        }
    }

    private void command(Operation next, byte[] frame) {
        operation = next;
        refreshDeadline();
        try {
            link.write(frame);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            fail("Could not send the MeshCore " + operationLabel() + " command.");
        }
    }

    private void refreshDeadline() {
        handler.removeCallbacks(deadline);
        handler.postDelayed(deadline, COMMAND_TIMEOUT_MS);
    }

    private void finishCommand() {
        handler.removeCallbacks(deadline);
        operation = null;
    }

    private void commandTimedOut() {
        if (operation != null) fail("MeshCore radio did not respond to the " + operationLabel() + " command.");
    }

    private String operationLabel() {
        return switch (operation) {
            case START -> "app start";
            case DEVICE -> "device information";
            case CONTACTS -> "contact list";
            case CHANNEL -> "channel lookup";
            case DRAIN -> "message retrieval";
            case SEND_DIRECT, SEND_CHANNEL -> "send message";
        };
    }

    private void fail(String information) {
        Events listener = events;
        close();
        if (listener != null) listener.failed(information);
    }

    private static int unsigned(byte value) { return Byte.toUnsignedInt(value); }

    private static int readInt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static String readString(byte[] data, int offset, int maximum) {
        return new String(data, offset, nulTerminatedLength(data, offset, maximum), StandardCharsets.UTF_8);
    }

    private static int nulTerminatedLength(byte[] data, int offset, int maximum) {
        int end = Math.min(offset + maximum, data.length);
        int cursor = offset;
        while (cursor < end && data[cursor] != 0) cursor++;
        return cursor - offset;
    }

    private static String hex(byte[] data, int offset, int length) {
        StringBuilder text = new StringBuilder(length * 2);
        for (int i = offset; i < offset + length; i++) {
            int value = unsigned(data[i]);
            text.append(Character.forDigit(value >>> 4, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }
}
