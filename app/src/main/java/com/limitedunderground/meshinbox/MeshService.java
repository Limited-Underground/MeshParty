package com.limitedunderground.meshinbox;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.limitedunderground.meshinbox.MeshDiagnostics.Event;
import com.limitedunderground.meshinbox.MeshDiagnostics.Radio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MeshService extends Service {
    public static final int CHAT_RULES_VERSION = 1;

    public enum Network {
        MESHTASTIC("Meshtastic", "alert_meshtastic", "meshtastic_messages", 20),
        MESHCORE("MeshCore", "alert_meshcore", "meshcore_messages", 21);

        private final String label;
        private final String alertPreference;
        private final String notificationChannel;
        private final int notificationId;

        Network(String label, String alertPreference, String notificationChannel,
                int notificationId) {
            this.label = label;
            this.alertPreference = alertPreference;
            this.notificationChannel = notificationChannel;
            this.notificationId = notificationId;
        }

        public String label() {
            return label;
        }
    }

    public record RadioState(String name, String address, String status, boolean active,
                             boolean ready, List<RadioProtocol.Destination> destinations) {}

    public record Message(long id, Network network, RadioProtocol.Destination replyTo,
                          String author, String body, long timestampMillis, boolean outgoing,
                          String radioAddress, String senderId) {
        public boolean canBlockSender() {
            return !outgoing && senderId != null;
        }

        public boolean canBlockSenderName() {
            return ChatModeration.canBlockSenderName(this);
        }

        public boolean canMuteChannel() {
            return !outgoing && network == Network.MESHCORE && replyTo != null && replyTo.channel()
                    && ChatModeration.validChannel(radioAddress, replyTo.id());
        }
    }

    public record BlockedSender(Network network, String id, String label) {}

    public record BlockedSenderName(String radioAddress, String channelId, String channelLabel,
                                    String label) {}

    public record MutedChannel(Network network, String radioAddress, String id, String label) {}

    public final class LocalBinder extends Binder {
        public MeshService service() {
            return MeshService.this;
        }
    }

    private static final String ACTION_START_RADIOS =
            "com.limitedunderground.meshinbox.START_RADIOS";
    private static final String ACTION_DISCONNECT_RADIOS =
            "com.limitedunderground.meshinbox.DISCONNECT_RADIOS";
    private static final String CONNECTION_CHANNEL = "connected_radios";
    private static final int CONNECTION_NOTIFICATION_ID = 19;
    private static final int HISTORY_LIMIT = 300;
    // Includes Android's first protected GATT operation and the full radio configuration sync.
    private static final long SYNC_TIMEOUT_MS = 90_000;
    private static final int[] RECONNECT_SECONDS = {1, 2, 4, 8, 16};
    private static final long SETUP_CONFIRM_TIMEOUT_MS = 120_000;
    private static final long SETUP_READBACK_DELAY_MS = 4_000;

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EnumMap<Network, Session> sessions = new EnumMap<>(Network.class);
    private final ArrayList<Message> history = new ArrayList<>();
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (prefs, key) -> {
                if ("reconnect".equals(key) && !prefs.getBoolean("reconnect", true)) {
                    for (Session session : sessions.values()) {
                        if (session.pendingRetry != null) session.deactivate("Offline");
                    }
                    publishRadioState();
                }
            };

    private SharedPreferences settings;
    private SharedPreferences storage;
    private ChatModeration moderation;
    private Message lastMeshCoreAlert;
    private NotificationManager notifications;
    private long nextMessageId = 1;
    private boolean foreground;
    private boolean startPending;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        storage = getSharedPreferences("messages", MODE_PRIVATE);
        moderation = new ChatModeration(settings);
        notifications = getSystemService(NotificationManager.class);
        for (Network network : Network.values()) sessions.put(network, new Session(network));
        loadHistory();
        createNotificationChannels();
        settings.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT_RADIOS.equals(action)) {
            for (Session session : sessions.values()) session.disconnectByUser();
            publishRadioState();
        } else if (ACTION_START_RADIOS.equals(action)) {
            startPending = false;
            // Even a connection cancelled while onStartCommand was queued must satisfy Android's
            // foreground start requirement before releasing the started service.
            try {
                startForeground(CONNECTION_NOTIFICATION_ID, connectionNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                foreground = true;
            } catch (IllegalStateException | SecurityException error) {
                for (Session session : sessions.values()) {
                    if (session.active) {
                        MeshDiagnostics.record(Event.GATT_SETUP_FAILED, session.radio);
                        session.deactivate("Bluetooth service unavailable");
                        session.notice = "Open MeshParty and check Nearby devices permission.";
                    }
                }
                publishRadioState();
                return START_NOT_STICKY;
            }
            for (Session session : sessions.values()) {
                if (session.active && session.transport == null && session.pendingRetry == null) {
                    session.beginConnection();
                }
            }
            publishRadioState();
        } else {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        for (Session session : sessions.values()) session.deactivate("Offline");
        listeners.clear();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    public List<Message> messages() {
        List<Message> visible = new ArrayList<>();
        for (Message message : history) {
            if (!moderation.hides(message)) visible.add(message);
        }
        return List.copyOf(visible);
    }

    public boolean chatRulesAccepted() {
        return moderation.rulesAccepted();
    }

    public void acceptChatRules() {
        moderation.acceptRules();
        publish();
    }

    public List<BlockedSender> blockedSenders() {
        return moderation.blockedSenders();
    }

    public List<BlockedSenderName> blockedSenderNames() {
        return moderation.blockedSenderNames();
    }

    public List<MutedChannel> mutedChannels() {
        return moderation.mutedChannels();
    }

    public boolean blockSender(Message message) {
        if (!moderation.block(message)) return false;
        notifications.cancel(message.network().notificationId);
        publish();
        return true;
    }

    public void unblockSender(BlockedSender sender) {
        if (moderation.unblock(sender)) publish();
    }

    public boolean blockSenderName(Message message) {
        if (!moderation.blockSenderName(message)) return false;
        if (lastMeshCoreAlert != null && moderation.hides(lastMeshCoreAlert)) {
            notifications.cancel(Network.MESHCORE.notificationId);
            lastMeshCoreAlert = null;
        }
        publish();
        return true;
    }

    public void unblockSenderName(BlockedSenderName sender) {
        if (moderation.unblockSenderName(sender)) publish();
    }

    public boolean muteChannel(Message message) {
        if (!moderation.mute(message)) return false;
        notifications.cancel(message.network().notificationId);
        publish();
        return true;
    }

    public void unmuteChannel(MutedChannel channel) {
        if (moderation.unmute(channel)) publish();
    }

    boolean isConnected(Network network) {
        return sessions.get(network).ready;
    }

    public RadioState state(Network network) {
        Session session = sessions.get(network);
        List<RadioProtocol.Destination> visible = new ArrayList<>();
        for (RadioProtocol.Destination target : session.destinations) {
            if (!moderation.blocksDestination(network, target)) visible.add(target);
        }
        return new RadioState(session.name, session.address, session.status, session.active,
                session.ready, List.copyOf(visible));
    }

    public String notice(Network network) {
        return sessions.get(network).notice;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    @SuppressLint("MissingPermission")
    public void connect(Network network, BluetoothDevice device) {
        Session session = sessions.get(network);
        MeshDiagnostics.record(Event.USER_CONNECT, session.radio);
        if (!hasConnectPermission()) {
            MeshDiagnostics.record(Event.GATT_SETUP_FAILED, session.radio);
            session.notice = "Allow Nearby devices to connect to this radio.";
            publish();
            return;
        }

        String address = device.getAddress();
        for (Session other : sessions.values()) {
            if (other != session && other.active && address.equals(other.address)) {
                session.notice = "This radio is already assigned to " + other.network.label() + ".";
                publish();
                return;
            }
        }
        if (session.active && address.equals(session.address)) return;

        String deviceName;
        try {
            deviceName = device.getName();
        } catch (SecurityException error) {
            MeshDiagnostics.record(Event.GATT_SETUP_FAILED, session.radio);
            session.notice = "Nearby devices permission was removed.";
            publish();
            return;
        }

        session.deactivate("Offline");
        session.device = device;
        session.address = address;
        session.name = isBlank(deviceName) ? network.label() : deviceName;
        session.status = "Connecting…";
        session.notice = "";
        session.active = true;
        if (foreground) {
            session.beginConnection();
        } else if (!startPending) {
            try {
                Intent start = new Intent(this, MeshService.class).setAction(ACTION_START_RADIOS);
                startForegroundService(start);
                startPending = true;
            } catch (IllegalStateException | SecurityException error) {
                MeshDiagnostics.record(Event.GATT_SETUP_FAILED, session.radio);
                session.deactivate("Bluetooth service unavailable");
                session.notice = "Keep MeshParty open while starting a radio connection.";
            }
        }
        publishRadioState();
    }

    public void disconnect(Network network) {
        Session session = sessions.get(network);
        session.disconnectByUser();
        session.notice = "";
        publishRadioState();
    }

    public String send(Network network, RadioProtocol.Destination destination, String body) {
        Session session = sessions.get(network);
        if (!chatRulesAccepted()) return session.rejectSend(getString(R.string.chat_rules_required_to_send));
        if (!session.ready) return session.rejectSend("Connect the " + network.label() + " radio first.");
        if (isBlank(body)) return session.rejectSend("Enter a message.");
        if (destination == null) return session.rejectSend("Choose a destination on " + network.label() + ".");

        RadioProtocol.Destination current = null;
        for (RadioProtocol.Destination available : session.destinations) {
            if (available.equals(destination)) {
                current = available;
                break;
            }
        }
        if (current == null) return session.rejectSend("Choose a current destination on " + network.label() + ".");
        if (moderation.blocksDestination(network, current)) {
            return session.rejectSend(getString(R.string.chat_blocked_destination));
        }

        long generation = session.generation;
        String address = session.address;
        try {
            session.protocol.send(current, body);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return session.rejectSend(error.getMessage() == null ? "The radio could not queue this message."
                    : error.getMessage());
        }
        if (generation != session.generation) return session.rejectSend("The radio disconnected during send.");
        MeshDiagnostics.record(Event.SEND_QUEUED, session.radio);
        appendMessage(network, current, null, "You", body, System.currentTimeMillis(), true, address);
        return null;
    }

    /** The Meshtastic radio's setup values, or null when it is not connected and synced. */
    public MeshtasticSetup.Current meshtasticSetup() {
        Session session = sessions.get(Network.MESHTASTIC);
        if (!session.ready || !(session.protocol instanceof MeshtasticProtocol meshtastic)) return null;
        return meshtastic.setupState();
    }

    /** True while MeshParty waits for the radio to confirm a setup change. */
    public boolean setupInProgress(Network network) {
        Session session = sessions.get(network);
        return session.expectedSetup != null || session.expectedMeshCore != null;
    }

    /** The MeshCore radio's preset values and name, or null when it is not connected and synced. */
    public MeshCoreSetup.Current meshCoreSetup() {
        Session session = sessions.get(Network.MESHCORE);
        if (!session.ready || !(session.protocol instanceof MeshCoreProtocol meshcore)) return null;
        return meshcore.setupState();
    }

    /** Sends the preset, name and optional advert to the MeshCore radio. Returns a problem, or null once started. */
    public String applyMeshCoreSetup(MeshCoreSetup.Request request) {
        Session session = sessions.get(Network.MESHCORE);
        if (!session.ready || !(session.protocol instanceof MeshCoreProtocol meshcore)) {
            return "Connect the MeshCore radio first.";
        }
        if (session.expectedMeshCore != null) return "Radio setup is already running.";
        try {
            meshcore.applySetup(request);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return error.getMessage() == null ? "The radio could not take these settings." : error.getMessage();
        }
        session.expectedMeshCore = request;
        session.notice = "Sending settings to the radio…";
        session.pendingSetupTimeout = () -> {
            session.pendingSetupTimeout = null;
            session.finishSetup("MeshParty could not confirm the new settings. Open Set up radio to check them.");
        };
        main.postDelayed(session.pendingSetupTimeout, SETUP_CONFIRM_TIMEOUT_MS);
        publish();
        return null;
    }

    /** Sends region, preset and names to the Meshtastic radio. Returns a problem, or null once started. */
    public String applyMeshtasticSetup(MeshtasticSetup.Request request) {
        Session session = sessions.get(Network.MESHTASTIC);
        if (!session.ready || !(session.protocol instanceof MeshtasticProtocol meshtastic)) {
            return "Connect the Meshtastic radio first.";
        }
        if (session.expectedSetup != null) return "Radio setup is already running.";
        try {
            meshtastic.applySetup(request);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return error.getMessage() == null ? "The radio could not take these settings." : error.getMessage();
        }
        session.expectedSetup = request;
        session.notice = "Sending settings to the radio…";
        session.pendingSetupTimeout = () -> {
            session.pendingSetupTimeout = null;
            session.finishSetup("MeshParty could not confirm the new settings. Open Set up radio to check them.");
        };
        main.postDelayed(session.pendingSetupTimeout, SETUP_CONFIRM_TIMEOUT_MS);
        publish();
        return null;
    }

    private void publish() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    private void publishRadioState() {
        boolean hasActiveRadio = sessions.values().stream().anyMatch(session -> session.active);
        if (foreground && hasActiveRadio && canShowAlerts()) {
            notifications.notify(CONNECTION_NOTIFICATION_ID, connectionNotification());
        } else if (!hasActiveRadio && !startPending) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
            }
            stopSelf();
        }
        publish();
    }

    private boolean hasConnectPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canShowAlerts() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.codePoints().allMatch(Character::isWhitespace);
    }

    private void createNotificationChannels() {
        NotificationChannel connection = new NotificationChannel(CONNECTION_CHANNEL,
                "Radio connections", NotificationManager.IMPORTANCE_LOW);
        connection.setShowBadge(false);
        notifications.createNotificationChannel(connection);
        for (Network network : Network.values()) {
            notifications.createNotificationChannel(new NotificationChannel(
                    network.notificationChannel, network.label() + " messages",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private Notification connectionNotification() {
        List<String> connected = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (session.ready) connected.add(session.network.label());
        }
        String text = connected.isEmpty() ? "Bluetooth radio connections active"
                : "Connected to " + String.join(" and ", connected);
        return new Notification.Builder(this, CONNECTION_CHANNEL)
                .setSmallIcon(R.drawable.ic_radio_status)
                .setContentTitle("MeshParty")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Disconnect radios",
                        PendingIntent.getService(this, 1,
                                new Intent(this, MeshService.class).setAction(ACTION_DISCONNECT_RADIOS),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                        .build())
                .build();
    }

    private PendingIntent openAppIntent() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) return null;
        return PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void notifyMessage(Message message) {
        Network network = message.network();
        if (!settings.getBoolean(network.alertPreference, true) || !canShowAlerts()) return;
        Notification notification = new Notification.Builder(this, network.notificationChannel)
                .setSmallIcon(R.drawable.ic_radio_status)
                .setContentTitle(network.label() + " · " + message.author())
                .setContentText(message.body())
                .setContentIntent(openAppIntent())
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();
        notifications.notify(network.notificationId, notification);
        if (network == Network.MESHCORE) lastMeshCoreAlert = message;
    }

    private void appendMessage(Network network, RadioProtocol.Destination replyTo, String senderId,
                               String author, String body, long timestampMillis, boolean outgoing,
                               String radioAddress) {
        String id = ChatModeration.senderId(network, senderId);
        if (!outgoing && moderation.hidesIncoming(network, id, replyTo, radioAddress, author)) return;
        long timestamp = timestampMillis > 0 ? timestampMillis : System.currentTimeMillis();
        Message message = new Message(nextMessageId++, network, replyTo, author, body, timestamp,
                outgoing, radioAddress, id);
        history.add(message);
        if (history.size() > HISTORY_LIMIT) history.remove(0);
        saveHistory();
        if (!outgoing) notifyMessage(message);
        publish();
    }

    private void loadHistory() {
        try {
            JSONArray records = new JSONArray(storage.getString("history", "[]"));
            for (int index = Math.max(0, records.length() - HISTORY_LIMIT);
                 index < records.length(); index++) {
                JSONObject record = records.optJSONObject(index);
                if (record == null) continue;
                try {
                    JSONObject target = record.optJSONObject("target");
                    RadioProtocol.Destination reply = target == null ? null
                            : new RadioProtocol.Destination(target.getString("id"),
                                    target.getString("label"), target.getBoolean("channel"));
                    long id = record.getLong("id");
                    Network network = Network.valueOf(record.getString("network"));
                    boolean outgoing = record.getBoolean("outgoing");
                    String sender = outgoing ? null
                            : ChatModeration.senderId(network, record.optString("senderId", null));
                    if (!outgoing && !record.has("senderId")) {
                        sender = ChatModeration.directSenderId(network, reply);
                    }
                    history.add(new Message(id, network, reply,
                            record.getString("author"), record.getString("body"),
                            record.getLong("time"), outgoing,
                            record.optString("radioAddress", null), sender));
                    nextMessageId = Math.max(nextMessageId, id + 1);
                } catch (JSONException | IllegalArgumentException malformedRecord) {
                    // Keep any other readable messages.
                }
            }
        } catch (JSONException malformedHistory) {
            // A damaged local history must not prevent a radio from connecting.
        }
    }

    private void saveHistory() {
        JSONArray records = new JSONArray();
        try {
            for (Message message : history) {
                JSONObject record = new JSONObject()
                        .put("id", message.id())
                        .put("network", message.network().name())
                        .put("author", message.author())
                        .put("body", message.body())
                        .put("time", message.timestampMillis())
                        .put("outgoing", message.outgoing())
                        .put("radioAddress", message.radioAddress())
                        .put("senderId", message.senderId() == null ? JSONObject.NULL : message.senderId());
                RadioProtocol.Destination reply = message.replyTo();
                if (reply != null) {
                    record.put("target", new JSONObject().put("id", reply.id())
                            .put("label", reply.label()).put("channel", reply.channel()));
                }
                records.put(record);
            }
        } catch (JSONException encodingError) {
            return;
        }
        storage.edit().putString("history", records.toString()).apply();
    }

    private final class Session {
        private final Network network;
        private final Radio radio;
        private String name = "";
        private String address = "";
        private String status = "Offline";
        private String notice = "";
        private boolean active;
        private boolean ready;
        private boolean previouslyReady;
        private int retryAttempt;
        private long generation;
        private BluetoothDevice device;
        private BleTransport transport;
        private RadioProtocol protocol;
        private Runnable pendingRetry;
        private Runnable pendingSyncTimeout;
        private Runnable pendingSetupTimeout;
        private Runnable pendingSetupReadback;
        private MeshtasticSetup.Request expectedSetup;
        private MeshCoreSetup.Request expectedMeshCore;
        private List<RadioProtocol.Destination> destinations = List.of();

        Session(Network network) {
            this.network = network;
            this.radio = switch (network) {
                case MESHTASTIC -> Radio.MESHTASTIC;
                case MESHCORE -> Radio.MESHCORE;
            };
        }

        private void beginConnection() {
            long token = ++generation;
            status = retryAttempt == 0 ? "Connecting…" : "Reconnecting…";
            protocol = network == Network.MESHTASTIC
                    ? new MeshtasticProtocol() : new MeshCoreProtocol();
            transport = new BleTransport(MeshService.this, protocol, radio, new BleTransport.Listener() {
                @Override
                public void onReady() {
                    if (!isCurrent(token)) return;
                    status = "Syncing radio…";
                    MeshDiagnostics.record(Event.PROTOCOL_SYNC_STARTED, radio);
                    pendingSyncTimeout = () -> fail(token, "Radio setup timed out.", Event.PROTOCOL_SYNC_TIMEOUT);
                    main.postDelayed(pendingSyncTimeout, SYNC_TIMEOUT_MS);
                    publishRadioState();
                    try {
                        protocol.open(transport, protocolEvents(token));
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        fail(token, "The radio could not start its messaging session.", Event.PROTOCOL_FAILED);
                    }
                }

                @Override
                public void onBytes(byte[] bytes) {
                    if (!isCurrent(token)) return;
                    try {
                        protocol.onBytes(bytes);
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        fail(token, error.getMessage(), Event.PROTOCOL_FAILED);
                    }
                }

                @Override
                public void onAvailable() {
                    if (!isCurrent(token)) return;
                    try {
                        protocol.onAvailable();
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        fail(token, error.getMessage(), Event.PROTOCOL_FAILED);
                    }
                }

                @Override
                public void onClosed(String reason) {
                    fail(token, reason, null);
                }
            });
            transport.connect(device);
            publishRadioState();
        }

        private RadioProtocol.Events protocolEvents(long token) {
            return new RadioProtocol.Events() {
                @Override
                public void ready() {
                    if (!isCurrent(token)) return;
                    cancelSyncTimeout();
                    MeshDiagnostics.record(Event.PROTOCOL_READY, radio);
                    ready = true;
                    previouslyReady = true;
                    retryAttempt = 0;
                    status = "Connected";
                    notice = "";
                    if (protocol instanceof MeshtasticProtocol meshtastic && expectedSetup == null) {
                        MeshtasticSetup.Current setup = meshtastic.setupState();
                        if (setup != null && !setup.regionSet()) {
                            notice = getString(R.string.setup_region_missing) + " Open Radios and tap Set up radio.";
                        }
                    }
                    checkSetup();
                    publishRadioState();
                }

                @Override
                public void destinations(List<RadioProtocol.Destination> available) {
                    if (!isCurrent(token)) return;
                    destinations = List.copyOf(available);
                    publish();
                }

                @Override
                public void message(RadioProtocol.Destination replyTo, String senderId, String author,
                                    String body, long timestampMillis) {
                    if (!isCurrent(token)) return;
                    appendMessage(network, replyTo, senderId, author, body, timestampMillis, false, address);
                }

                @Override
                public void notice(String information) {
                    if (!isCurrent(token)) return;
                    notice = information;
                    publish();
                }

                @Override
                public void failed(String information) {
                    fail(token, information, Event.PROTOCOL_FAILED);
                }

                @Override
                public void setupChanged() {
                    if (!isCurrent(token)) return;
                    checkSetup();
                    publish();
                }

                @Override
                public void setupApplied() {
                    if (!isCurrent(token) || expectedSetup == null) return;
                    notice = "Settings sent. The radio may restart; MeshParty reconnects and checks them.";
                    cancelSetupReadback();
                    pendingSetupReadback = () -> {
                        pendingSetupReadback = null;
                        if (isCurrent(token) && ready && protocol instanceof MeshtasticProtocol meshtastic) {
                            try {
                                meshtastic.refreshSetup();
                            } catch (IllegalArgumentException | IllegalStateException ignored) {
                                // The link dropped; the reconnect handshake reads the settings instead.
                            }
                        }
                    };
                    main.postDelayed(pendingSetupReadback, SETUP_READBACK_DELAY_MS);
                    publish();
                }

                @Override
                public void setupFailed(String information) {
                    if (!isCurrent(token)) return;
                    finishSetup(information);
                }
            };
        }

        private boolean isCurrent(long token) {
            return active && token == generation;
        }

        private void fail(long token, String reason, Event failureEvent) {
            if (!isCurrent(token)) return;
            if (failureEvent != null) MeshDiagnostics.record(failureEvent, radio);
            notice = isBlank(reason) ? "Bluetooth connection lost." : reason;
            closeLink();
            if (previouslyReady && settings.getBoolean("reconnect", true) && hasConnectPermission()
                    && retryAttempt < RECONNECT_SECONDS.length) {
                int delaySeconds = RECONNECT_SECONDS[retryAttempt++];
                status = "Reconnecting in " + delaySeconds + " s";
                MeshDiagnostics.record(Event.RECONNECT_SCHEDULED, radio, delaySeconds);
                pendingRetry = () -> {
                    pendingRetry = null;
                    beginConnection();
                };
                main.postDelayed(pendingRetry, delaySeconds * 1_000L);
            } else {
                active = false;
                status = "Offline";
            }
            publishRadioState();
        }

        private String rejectSend(String userNotice) {
            MeshDiagnostics.record(Event.SEND_REJECTED, radio);
            return userNotice;
        }

        private void disconnectByUser() {
            if (active) MeshDiagnostics.record(Event.USER_DISCONNECT, radio);
            deactivate("Offline");
        }

        private void checkSetup() {
            if (expectedMeshCore != null && protocol instanceof MeshCoreProtocol meshcore) {
                MeshCoreSetup.Current current = meshcore.setupState();
                if (!MeshCoreSetup.matches(current, expectedMeshCore)) {
                    finishSetup("The radio did not keep the new settings. Open Set up radio to check them.");
                    return;
                }
                finishSetup("Radio set up: " + MeshCoreSetup.describe(current) + ", name " + current.name() + ".");
                return;
            }
            if (expectedSetup == null || !(protocol instanceof MeshtasticProtocol meshtastic)) return;
            MeshtasticSetup.Current current = meshtastic.setupState();
            if (!MeshtasticSetup.matches(current, expectedSetup)) return;
            String preset = current.usePreset() ? MeshtasticSetup.presetLabel(current.preset()) : "custom settings";
            finishSetup("Radio set up: " + MeshtasticSetup.regionLabel(current.region()) + ", " + preset + ".");
        }

        private void finishSetup(String result) {
            expectedSetup = null;
            expectedMeshCore = null;
            cancelSetupReadback();
            if (pendingSetupTimeout != null) {
                main.removeCallbacks(pendingSetupTimeout);
                pendingSetupTimeout = null;
            }
            notice = result;
            publish();
        }

        private void cancelSetupReadback() {
            if (pendingSetupReadback != null) {
                main.removeCallbacks(pendingSetupReadback);
                pendingSetupReadback = null;
            }
        }

        private void deactivate(String newStatus) {
            if (expectedSetup != null || expectedMeshCore != null) finishSetup("");
            active = false;
            previouslyReady = false;
            retryAttempt = 0;
            status = newStatus;
            if (pendingRetry != null) {
                main.removeCallbacks(pendingRetry);
                pendingRetry = null;
            }
            closeLink();
        }

        private void closeLink() {
            ++generation;
            cancelSyncTimeout();
            ready = false;
            destinations = List.of();
            if (protocol != null) protocol.close();
            if (transport != null) transport.disconnect();
            protocol = null;
            transport = null;
        }

        private void cancelSyncTimeout() {
            if (pendingSyncTimeout != null) {
                main.removeCallbacks(pendingSyncTimeout);
                pendingSyncTimeout = null;
            }
        }
    }
}
