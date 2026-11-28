package com.limitedunderground.meshinbox;

import java.util.List;
import java.util.UUID;

/** Commands and events for one companion-radio connection, all on the main thread. */
public interface RadioProtocol {
    UUID serviceId();
    UUID writeId();
    UUID readId();
    UUID notifyId();
    boolean framesInNotifications();
    void open(Link link, Events events);
    void onBytes(byte[] bytes);
    void onAvailable();
    void close();
    /** Queues a text message and returns an ID that later delivery() events refer to. */
    long send(Destination destination, String text);

    /** Delivery of a sent message, from first to final (0.2.3). */
    int SENDING = 0;
    /** The radio took the message; for a channel this is final (or a relay was heard). */
    int SENT = 1;
    /** The recipient confirmed a direct message. */
    int DELIVERED = 2;
    /** The radio refused it or no confirmation came. */
    int FAILED = 3;

    record Destination(String id, String label, boolean channel) {}

    /**
     * A node the radio has heard or keeps as a contact. lastHeardMillis is 0 when unknown;
     * snr and hops are null when the radio does not report them (hops 0 means direct).
     */
    record Node(String id, String name, String shortId, int kind, long lastHeardMillis, Float snr, Integer hops) {
        public static final int MESHTASTIC = 0;
        public static final int COMPANION = 1;
        public static final int REPEATER = 2;
        public static final int ROOM = 3;
        public static final int SENSOR = 4;
    }

    interface Link {
        void write(byte[] frame);
        void read();
    }

    interface Events {
        void ready();
        void destinations(List<Destination> destinations);
        /**
         * The reply target is null when the conversation has no usable address.
         * The sender ID is null when the radio cannot supply a stable identifier.
         */
        void message(Destination replyTo, String senderId, String author, String body,
                     long timestampMillis);
        void notice(String information);
        void failed(String information);
        /** A sent message moved on: SENT, DELIVERED or FAILED. */
        default void delivery(long sendId, int state) {}
        /** The nodes this radio knows about, replacing the previous list. */
        default void nodes(List<Node> nodes) {}
        /** The radio reported new setup values (region, preset or names). */
        default void setupChanged() {}
        /** All setup changes were handed to the radio; it may now restart. */
        default void setupApplied() {}
        /** The radio refused or could not take the setup changes. */
        default void setupFailed(String information) {}
    }
}
