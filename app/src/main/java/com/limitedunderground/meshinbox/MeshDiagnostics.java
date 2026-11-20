package com.limitedunderground.meshinbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Local diagnostics with no entry point for free text or raw Bluetooth data. */
final class MeshDiagnostics {
    enum Radio { NONE, MESHTASTIC, MESHCORE }

    enum Event {
        APP_STARTED, ACTIVITY_START, ACTIVITY_READY, APP_CRASH,
        SCAN_STARTED, SCAN_FINISHED, SCAN_FAILED(Detail.SCAN_STATUS),
        USER_CONNECT, USER_DISCONNECT, GATT_CONNECT_STARTED,
        GATT_CONNECT_FAILED(Detail.BLUETOOTH_STATUS), GATT_CONNECTED,
        GATT_DISCONNECTED(Detail.BLUETOOTH_STATUS), GATT_MTU(Detail.MTU),
        GATT_MTU_FAILED(Detail.BLUETOOTH_STATUS), GATT_DISCOVERED,
        GATT_DISCOVERY_FAILED(Detail.BLUETOOTH_STATUS), GATT_SUBSCRIBED,
        GATT_SUBSCRIPTION_FAILED(Detail.BLUETOOTH_STATUS),
        GATT_READ_FAILED(Detail.BLUETOOTH_STATUS), GATT_WRITE_FAILED(Detail.BLUETOOTH_STATUS),
        GATT_TIMEOUT(Detail.STAGE), GATT_SETUP_FAILED(Detail.STAGE),
        PROTOCOL_SYNC_STARTED, PROTOCOL_READY, PROTOCOL_SYNC_TIMEOUT, PROTOCOL_FAILED,
        RECONNECT_SCHEDULED(Detail.DELAY_SECONDS), SEND_QUEUED, SEND_REJECTED;

        final Detail detail;

        Event() { this(Detail.NONE); }
        Event(Detail detail) { this.detail = detail; }
    }

    private enum Detail {
        NONE("", 0, -1),
        BLUETOOTH_STATUS("status", 0, 4095),
        SCAN_STATUS("status", 0, 255),
        MTU("mtu", 23, 517),
        STAGE("stage", 1, 6),
        DELAY_SECONDS("delay_s", 1, 60);

        final String label;
        final int minimum;
        final int maximum;

        Detail(String label, int minimum, int maximum) {
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        boolean accepts(int value) {
            return value == NO_DETAIL || (value >= minimum && value <= maximum)
                    || (this == BLUETOOTH_STATUS && value == Integer.MAX_VALUE);
        }

        String display(int value) {
            if (this == BLUETOOTH_STATUS && value == Integer.MAX_VALUE) return "unknown";
            if (this != STAGE) return Integer.toString(value);
            return switch (value) {
                case 1 -> "connect";
                case 2 -> "mtu";
                case 3 -> "discovery";
                case 4 -> "subscription";
                case 5 -> "read";
                case 6 -> "write";
                default -> "unknown";
            };
        }
    }

    static final int HISTORY_LIMIT = 200;
    static final long MAX_ELAPSED_MS = TimeUnit.DAYS.toMillis(7);
    private static final int NO_DETAIL = -1;
    private static final int MAX_STORED_CHARS = HISTORY_LIMIT * 100;
    private static final String HISTORY_KEY = "events-v1";
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}");
    private static final ReentrantLock LOCK = new ReentrantLock();

    private static boolean initialized;
    private static SharedPreferences storage;
    private static History history = new History();
    private static long sessionStartMs;

    private MeshDiagnostics() {}

    static void initialize(Context context) {
        LOCK.lock();
        try {
            if (initialized) return;
            try {
                storage = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
                history = History.decode(storage.getString(HISTORY_KEY, ""));
            } catch (RuntimeException ignored) {
                // A missing or malformed local history must never stop the app from starting.
            }
            sessionStartMs = SystemClock.elapsedRealtime();
            initialized = true;
            append(Event.APP_STARTED, Radio.NONE, NO_DETAIL);
        } finally {
            LOCK.unlock();
        }
    }

    static void record(Event event) { record(event, Radio.NONE); }

    static void record(Event event, Radio radio) { record(event, radio, NO_DETAIL); }

    static void record(Event event, Radio radio, int detail) {
        LOCK.lock();
        try {
            if (initialized && event != null && radio != null) append(event, radio, detail);
        } finally {
            LOCK.unlock();
        }
    }

    static void recordUncaughtCrash() {
        if (!LOCK.tryLock()) return;
        try {
            if (initialized) append(Event.APP_CRASH, Radio.NONE, NO_DETAIL);
        } finally {
            LOCK.unlock();
        }
    }

    // Called only with LOCK held. No caller data is admitted except the typed, bounded detail.
    private static void append(Event event, Radio radio, int detail) {
        long elapsed = Math.min(MAX_ELAPSED_MS, Math.max(0, SystemClock.elapsedRealtime() - sessionStartMs));
        history.add(new Entry(event, radio, elapsed, event.detail.accepts(detail) ? detail : NO_DETAIL));
        save();
    }

    private static void save() {
        if (storage == null) return;
        try {
            storage.edit().putString(HISTORY_KEY, history.encode()).apply();
        } catch (RuntimeException ignored) {
            // Retain the in-memory entries if Android storage is briefly unavailable.
        }
    }

    static void clear() {
        LOCK.lock();
        try {
            history = new History();
            sessionStartMs = SystemClock.elapsedRealtime();
            save();
        } finally {
            LOCK.unlock();
        }
    }

    static String exportReport(Context context) {
        initialize(context);
        String appVersion = "unknown";
        long versionCode = -1;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            appVersion = safeVersion(info.versionName);
            versionCode = info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Export the events even if version metadata is unavailable.
        }
        StringBuilder report = new StringBuilder("MeshParty diagnostics v1\n");
        report.append("App version: ").append(appVersion).append('\n');
        if (versionCode >= 0) report.append("App version code: ").append(versionCode).append('\n');
        report.append("Android API level: ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("Times are elapsed from the app session's start or a diagnostics clear.\n")
                .append("Elapsed values are capped at seven days. Oldest entries can roll off.\n")
                .append("Status is an Android Bluetooth code. MTU is in bytes. Reconnect delays are in seconds.\n");
        LOCK.lock();
        try {
            history.appendReport(report);
        } finally {
            LOCK.unlock();
        }
        return report.toString();
    }

    static String safeVersion(String version) {
        return version != null && VERSION_PATTERN.matcher(version).matches() ? version : "unknown";
    }

    record Entry(Event event, Radio radio, long elapsedMs, int detail) {}

    static final class History {
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();

        void add(Entry entry) {
            if (entry.event() == null || entry.radio() == null || entry.elapsedMs() < 0
                    || entry.elapsedMs() > MAX_ELAPSED_MS || !entry.event().detail.accepts(entry.detail())) return;
            if (entries.size() == HISTORY_LIMIT) entries.removeFirst();
            entries.addLast(entry);
        }

        String encode() {
            StringBuilder out = new StringBuilder();
            for (Entry entry : entries) {
                out.append(entry.event()).append('|').append(entry.radio()).append('|')
                        .append(entry.elapsedMs()).append('|').append(entry.detail()).append('\n');
            }
            return out.toString();
        }

        static History decode(String encoded) {
            History loaded = new History();
            if (encoded == null || encoded.length() > MAX_STORED_CHARS) return loaded;
            for (String row : encoded.split("\n")) {
                String[] fields = row.split("\\|", -1);
                if (fields.length != 4) continue;
                try {
                    Event event = Event.valueOf(fields[0]);
                    Radio radio = Radio.valueOf(fields[1]);
                    long elapsed = Long.parseLong(fields[2]);
                    int detail = Integer.parseInt(fields[3]);
                    loaded.add(new Entry(event, radio, elapsed, detail));
                } catch (IllegalArgumentException ignored) {
                    // Reconstruct each retained record from the schema instead of echoing its input text.
                }
            }
            return loaded;
        }

        void appendReport(StringBuilder out) {
            out.append("Retained events: ").append(entries.size()).append('/').append(HISTORY_LIMIT).append('\n');
            int session = 0;
            for (Entry entry : entries) {
                if (session == 0 || entry.event() == Event.APP_STARTED) out.append("\nSession ").append(++session).append('\n');
                out.append('+').append(entry.elapsedMs()).append(" ms ");
                if (entry.radio() != Radio.NONE) out.append(entry.radio()).append(' ');
                out.append(entry.event());
                if (entry.detail() != NO_DETAIL) {
                    out.append(' ').append(entry.event().detail.label).append('=')
                            .append(entry.event().detail.display(entry.detail()));
                }
                out.append('\n');
            }
        }
    }
}
