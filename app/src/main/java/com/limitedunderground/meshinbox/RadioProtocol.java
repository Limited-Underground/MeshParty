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
    void send(Destination destination, String text);

    record Destination(String id, String label, boolean channel) {}

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
    }
}
