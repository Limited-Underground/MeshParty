package com.limitedunderground.meshinbox;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.limitedunderground.meshinbox.MeshDiagnostics.Event;
import com.limitedunderground.meshinbox.MeshDiagnostics.Radio;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.IntSupplier;

/** Owns one BLE connection. All transport methods and listener calls run on the main thread. */
@SuppressLint("MissingPermission")
public final class BleTransport implements RadioProtocol.Link {
    public interface Listener {
        void onReady();
        void onBytes(byte[] bytes);
        void onAvailable();
        void onClosed(String reason);
    }

    private enum Stage {
        CLOSED(0), CONNECTING(1), NEGOTIATING_MTU(2), DISCOVERING(3), SUBSCRIBING(4), READY(0);

        // Stable stage values in support reports. READY uses the active read/write operation.
        final int diagnosticCode;

        Stage(int diagnosticCode) {
            this.diagnosticCode = diagnosticCode;
        }
    }

    // A read has no frame. Each write and each delivered read/notification is one protocol frame.
    private record Operation(byte[] frame) {
        boolean isRead() { return frame == null; }
        String description() { return isRead() ? "Reading from the radio" : "Writing to the radio"; }
    }

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int DEFAULT_ATT_MTU = 23;
    private static final int REQUESTED_ATT_MTU = 517;
    private static final int ATT_HEADER_SIZE = 3;
    private static final int MAX_CHARACTERISTIC_VALUE = 512;
    private static final int MAX_PENDING_OPERATIONS = 48;
    private static final long CONNECT_TIMEOUT_MS = 30_000;
    private static final long OPERATION_TIMEOUT_MS = 12_000;
    private static final long FIRST_OPERATION_TIMEOUT_MS = 45_000;
    private static final int START_FAILED_WITHOUT_STATUS = -1;
    private static final String CONNECT_PERMISSION_MESSAGE = "Allow Nearby devices permission to connect to this radio.";

    private final Context context;
    private final RadioProtocol protocol;
    private final Radio radio;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Operation> pending = new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;
    private BluetoothGattCharacteristic notification;
    private Stage stage = Stage.CLOSED;
    private Operation active;
    private Runnable watchdog;
    private int attMtu = DEFAULT_ATT_MTU;
    private int writeType;
    private boolean firstOperation;

    public BleTransport(Context context, RadioProtocol protocol, Radio radio, Listener listener) {
        this.context = context.getApplicationContext();
        this.protocol = protocol;
        this.radio = radio;
        this.listener = listener;
    }

    public void connect(BluetoothDevice device) {
        disconnect();
        stage = Stage.CONNECTING;
        MeshDiagnostics.record(Event.GATT_CONNECT_STARTED, radio);
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException error) {
            failSetup(CONNECT_PERMISSION_MESSAGE);
            return;
        }
        if (gatt == null) {
            MeshDiagnostics.record(Event.GATT_CONNECT_FAILED, radio);
            fail("Android could not start the Bluetooth connection.");
            return;
        }
        armTimeout(CONNECT_TIMEOUT_MS, "Connecting to the radio timed out.");
    }

    /** Releases this connection and discards its operations after an explicit disconnect. */
    public void disconnect() {
        BluetoothGatt oldGatt = gatt;
        // Detach the old GATT first so any pending callback cannot affect a future session.
        gatt = null;
        stage = Stage.CLOSED;
        pending.clear();
        active = null;
        tx = rx = notification = null;
        attMtu = DEFAULT_ATT_MTU;
        firstOperation = true;
        disarmTimeout();

        if (oldGatt == null) return;
        try {
            oldGatt.disconnect();
        } catch (SecurityException ignored) {
            // Android permission may have been revoked during the connection.
        }
        try {
            oldGatt.close();
        } catch (SecurityException ignored) {
            // Local ownership has already been cleared even if the framework rejects cleanup.
        }
    }

    @Override
    public void write(byte[] frame) {
        requireQueueSpace();
        int maxFrame = Math.min(MAX_CHARACTERISTIC_VALUE, attMtu - ATT_HEADER_SIZE);
        if (frame.length == 0 || frame.length > maxFrame) {
            throw new IllegalArgumentException("This Bluetooth connection supports frames up to "
                + maxFrame + " bytes. Shorten the message or reconnect the radio.");
        }
        pending.addLast(new Operation(frame.clone()));
        main.post(this::pump);
    }

    @Override
    public void read() {
        requireQueueSpace();
        if (rx == null) throw new IllegalStateException("This radio delivers frames by notification.");
        pending.addLast(new Operation(null));
        main.post(this::pump);
    }

    private void requireQueueSpace() {
        if (stage != Stage.READY) throw new IllegalStateException("The Bluetooth radio is disconnected.");
        if (pending.size() >= MAX_PENDING_OPERATIONS) {
            throw new IllegalStateException("The Bluetooth radio is busy. Try again in a moment.");
        }
    }

    private void pump() {
        if (stage != Stage.READY || active != null || pending.isEmpty()) return;
        active = pending.removeFirst();
        Operation operation = active;
        startOperation(operation.description(),
            firstOperation ? FIRST_OPERATION_TIMEOUT_MS : OPERATION_TIMEOUT_MS,
            operation.isRead() ? Event.GATT_READ_FAILED : Event.GATT_WRITE_FAILED,
            () -> operation.isRead() ? startStatus(gatt.readCharacteristic(rx)) : writeFrame(operation.frame()));
    }

    @SuppressWarnings("deprecation")
    private int writeFrame(byte[] frame) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeCharacteristic(tx, frame, writeType);
        }
        tx.setWriteType(writeType);
        return startStatus(tx.setValue(frame) && gatt.writeCharacteristic(tx));
    }

    private void negotiateMtu() {
        stage = Stage.NEGOTIATING_MTU;
        try {
            if (!gatt.requestMtu(REQUESTED_ATT_MTU)) {
                MeshDiagnostics.record(Event.GATT_MTU_FAILED, radio);
                discoverServices();
                return;
            }
            armTimeout(OPERATION_TIMEOUT_MS, "Negotiating Bluetooth packet size timed out.");
        } catch (SecurityException error) {
            failSetup(CONNECT_PERMISSION_MESSAGE);
        }
    }

    private void discoverServices() {
        stage = Stage.DISCOVERING;
        startOperation("Discovering the radio's Bluetooth services", OPERATION_TIMEOUT_MS,
            Event.GATT_DISCOVERY_FAILED, () -> startStatus(gatt.discoverServices()));
    }

    private void subscribe() {
        BluetoothGattService service = gatt.getService(protocol.serviceId());
        if (service == null) {
            failSetup("This device does not expose the selected radio's Bluetooth service.");
            return;
        }
        tx = service.getCharacteristic(protocol.writeId());
        notification = service.getCharacteristic(protocol.notifyId());
        if (!protocol.framesInNotifications()) rx = service.getCharacteristic(protocol.readId());
        if (tx == null || notification == null || (!protocol.framesInNotifications()
            && (rx == null || (rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0))) {
            failSetup("The radio's Bluetooth service is missing a messaging characteristic.");
            return;
        }
        int writeProperties = tx.getProperties();
        if ((writeProperties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        } else if ((writeProperties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        } else {
            failSetup("The radio's Bluetooth messaging characteristic does not accept writes.");
            return;
        }

        BluetoothGattDescriptor descriptor = notification.getDescriptor(CCCD);
        int notifyProperties = notification.getProperties();
        byte[] subscription;
        if ((notifyProperties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            subscription = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        } else if ((notifyProperties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            subscription = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        } else {
            subscription = null;
        }
        stage = Stage.SUBSCRIBING;
        if (descriptor == null || subscription == null) {
            failSetup("The radio's Bluetooth messaging notifications are unavailable.");
            return;
        }
        startOperation("Subscribing to radio notifications", FIRST_OPERATION_TIMEOUT_MS,
            Event.GATT_SUBSCRIPTION_FAILED, () -> gatt.setCharacteristicNotification(notification, true)
                ? enableNotifications(descriptor, subscription) : START_FAILED_WITHOUT_STATUS);
    }

    @SuppressWarnings("deprecation")
    private int enableNotifications(BluetoothGattDescriptor descriptor, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeDescriptor(descriptor, value);
        }
        return startStatus(descriptor.setValue(value) && gatt.writeDescriptor(descriptor));
    }

    private static int startStatus(boolean started) {
        return started ? BluetoothStatusCodes.SUCCESS : START_FAILED_WITHOUT_STATUS;
    }

    private void startOperation(String description, long timeout, Event failureEvent, IntSupplier start) {
        try {
            int status = start.getAsInt();
            if (status != BluetoothStatusCodes.SUCCESS) {
                if (status == START_FAILED_WITHOUT_STATUS) MeshDiagnostics.record(failureEvent, radio);
                else MeshDiagnostics.record(failureEvent, radio, status);
                fail(description + " could not start.");
                return;
            }
            armTimeout(timeout, description + " timed out.");
        } catch (SecurityException error) {
            failSetup(CONNECT_PERMISSION_MESSAGE);
        }
    }

    private void armTimeout(long delay, String reason) {
        disarmTimeout();
        int timeoutStage = diagnosticStage();
        watchdog = () -> {
            MeshDiagnostics.record(Event.GATT_TIMEOUT, radio, timeoutStage);
            fail(reason);
        };
        main.postDelayed(watchdog, delay);
    }

    private void disarmTimeout() {
        if (watchdog == null) return;
        main.removeCallbacks(watchdog);
        watchdog = null;
    }

    private void fail(String reason) {
        disconnect();
        listener.onClosed(reason);
    }

    private void failSetup(String reason) {
        MeshDiagnostics.record(Event.GATT_SETUP_FAILED, radio, diagnosticStage());
        fail(reason);
    }

    private int diagnosticStage() {
        if (stage == Stage.READY && active != null) return active.isRead() ? 5 : 6;
        return stage.diagnosticCode;
    }

    private String statusReason(String action, int status) {
        if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
            || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION) {
            return "Bluetooth authentication failed. Pair this radio with Android and retry.";
        }
        return action + " (Bluetooth status " + status + ").";
    }

    private void finishOperation(BluetoothGatt source, BluetoothGattCharacteristic characteristic,
                                 int status, boolean read, byte[] value) {
        if (source != gatt || stage != Stage.READY || active == null || active.isRead() != read
            || !(read ? rx : tx).getUuid().equals(characteristic.getUuid())) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            MeshDiagnostics.record(read ? Event.GATT_READ_FAILED : Event.GATT_WRITE_FAILED, radio, status);
            fail(statusReason(active.description() + " failed", status));
            return;
        }
        active = null;
        firstOperation = false;
        disarmTimeout();
        if (read) listener.onBytes(value);
        pump();
    }

    private void postNotification(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
        main.post(() -> {
            if (source != gatt || stage != Stage.READY
                || !notification.getUuid().equals(characteristic.getUuid())) return;
            if (protocol.framesInNotifications()) listener.onBytes(value);
            else listener.onAvailable();
        });
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
            main.post(() -> {
                if (source != gatt) return;
                boolean failed = status != BluetoothGatt.GATT_SUCCESS;
                if (failed || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Event event = stage == Stage.CONNECTING ? Event.GATT_CONNECT_FAILED : Event.GATT_DISCONNECTED;
                    MeshDiagnostics.record(event, radio, status);
                    fail(failed ? statusReason("The Bluetooth connection ended", status) : "The Bluetooth radio disconnected.");
                } else if (newState == BluetoothProfile.STATE_CONNECTED && stage == Stage.CONNECTING) {
                    MeshDiagnostics.record(Event.GATT_CONNECTED, radio);
                    negotiateMtu();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt source, int mtu, int status) {
            main.post(() -> {
                if (source != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    attMtu = Math.max(mtu, DEFAULT_ATT_MTU);
                    MeshDiagnostics.record(Event.GATT_MTU, radio, attMtu);
                } else {
                    MeshDiagnostics.record(Event.GATT_MTU_FAILED, radio, status);
                }
                if (stage == Stage.NEGOTIATING_MTU) discoverServices();
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt source, int status) {
            main.post(() -> {
                if (source != gatt || stage != Stage.DISCOVERING) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    MeshDiagnostics.record(Event.GATT_DISCOVERED, radio);
                    subscribe();
                } else {
                    MeshDiagnostics.record(Event.GATT_DISCOVERY_FAILED, radio, status);
                    fail(statusReason("Could not discover the radio's Bluetooth services", status));
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if (source != gatt || stage != Stage.SUBSCRIBING || !CCCD.equals(descriptor.getUuid())
                    || !notification.getUuid().equals(descriptor.getCharacteristic().getUuid())) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    MeshDiagnostics.record(Event.GATT_SUBSCRIPTION_FAILED, radio, status);
                    fail(statusReason("Could not subscribe to radio notifications", status));
                    return;
                }
                disarmTimeout();
                stage = Stage.READY;
                MeshDiagnostics.record(Event.GATT_SUBSCRIBED, radio);
                listener.onReady();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            main.post(() -> finishOperation(source, characteristic, status, false, null));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic characteristic,
                                         byte[] value, int status) {
            byte[] copy = value == null ? new byte[0] : value.clone();
            main.post(() -> finishOperation(source, characteristic, status, true, copy));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
            postNotification(source, characteristic, value.clone());
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            if (Build.VERSION.SDK_INT < 33) onCharacteristicRead(source, characteristic, characteristic.getValue(), status);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] value = characteristic.getValue();
                if (value != null) postNotification(source, characteristic, value.clone());
            }
        }

        @Override
        public void onServiceChanged(BluetoothGatt source) {
            main.post(() -> {
                if (source == gatt && stage == Stage.READY) {
                    MeshDiagnostics.record(Event.GATT_SETUP_FAILED, radio, Stage.DISCOVERING.diagnosticCode);
                    fail("The radio's Bluetooth services changed. Reconnect the radio.");
                }
            });
        }
    };
}
