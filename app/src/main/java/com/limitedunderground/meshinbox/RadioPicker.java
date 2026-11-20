package com.limitedunderground.meshinbox;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class RadioPicker {
    private static final ParcelUuid MESHTASTIC_SERVICE = ParcelUuid.fromString(
            "6ba1b218-15a8-461f-9fa8-5dcae273eafd");
    private static final ParcelUuid MESHCORE_SERVICE = ParcelUuid.fromString(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final long SCAN_DURATION_MILLIS = 12_000;
    private static final int MAX_CANDIDATES = 150;

    private final Activity activity;
    private final MeshService.Network network;
    private final MeshDiagnostics.Radio diagnosticRadio;
    private final Consumer<BluetoothDevice> onChosen;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();

    private AlertDialog dialog;
    private TextView status;
    private CheckBox showAll;
    private ArrayAdapter<Candidate> rows;
    private BluetoothLeScanner scanner;
    private ScanCallback activeScan;
    private Runnable finishScan;

    public RadioPicker(Activity activity, MeshService.Network network,
                       Consumer<BluetoothDevice> onChosen) {
        this.activity = activity;
        this.network = network;
        this.diagnosticRadio = MeshDiagnostics.Radio.valueOf(network.name());
        this.onChosen = onChosen;
    }

    public void show() {
        close();
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        Context context = builder.getContext();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), 0);

        status = new TextView(context);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);

        showAll = new CheckBox(context);
        showAll.setText(R.string.show_all_devices);
        showAll.setOnCheckedChangeListener((button, checked) -> refreshRows());
        content.addView(showAll);

        rows = new ArrayAdapter<>(context, android.R.layout.simple_list_item_2, android.R.id.text1) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                Candidate candidate = getItem(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText(candidate.name);
                String detail = candidate.bonded ? "Paired  •  " : "Nearby  •  ";
                ((TextView) row.findViewById(android.R.id.text2)).setText(
                        detail + candidate.device.getAddress());
                return row;
            }

            @Override
            public long getItemId(int position) {
                return Long.parseLong(getItem(position).device.getAddress().replace(":", ""), 16);
            }

            @Override
            public boolean hasStableIds() {
                return true;
            }
        };

        ListView devices = new ListView(context);
        devices.setAdapter(rows);
        devices.setOnItemClickListener((parent, row, position, id) -> {
            Candidate candidate = (Candidate) parent.getItemAtPosition(position);
            if (candidate != null && rows.getItemId(position) == id) {
                BluetoothDevice chosen = candidate.device;
                close();
                onChosen.accept(chosen);
            }
        });
        int listHeight = Math.min(dp(260), activity.getResources().getDisplayMetrics().heightPixels / 3);
        content.addView(devices, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        dialog = builder.setTitle("Choose " + network.label() + " radio")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Scan again", null)
                .create();
        dialog.setOnDismissListener(dismissed -> {
            if (dialog == dismissed) {
                dialog = null;
                stopScan();
            }
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button -> startScan());
        startScan();
    }

    public void close() {
        AlertDialog oldDialog = dialog;
        dialog = null;
        stopScan();
        if (oldDialog != null) {
            oldDialog.dismiss();
        }
    }

    private void startScan() {
        stopScan();
        candidates.clear();
        refreshRows();
        if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
                || activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.allow_nearby_devices);
            return;
        }

        BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter == null || !adapter.isEnabled()) {
                status.setText(R.string.turn_on_bluetooth);
                return;
            }
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                int type = device.getType();
                if (type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                    addCandidate(device, null, true);
                }
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                status.setText(R.string.scan_unavailable_paired);
                return;
            }

            ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    main.post(() -> recordResults(this, List.of(result)));
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    main.post(() -> recordResults(this, results));
                }

                @Override
                public void onScanFailed(int errorCode) {
                    main.post(() -> {
                        if (activeScan == this) {
                            MeshDiagnostics.record(MeshDiagnostics.Event.SCAN_FAILED, diagnosticRadio, errorCode);
                            stopScan();
                            status.setText(R.string.scan_unavailable_retry);
                        }
                    });
                }
            };
            activeScan = callback;
            scanner.startScan(List.of(), new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
            MeshDiagnostics.record(MeshDiagnostics.Event.SCAN_STARTED, diagnosticRadio);
            status.setText(activity.getString(R.string.scanning_for_radios, network.label()));
            finishScan = () -> {
                if (activeScan == callback) {
                    stopScan();
                    status.setText(candidates.values().stream().anyMatch(candidate -> candidate.matches)
                            ? R.string.scan_complete_choose : R.string.scan_no_likely_radios);
                }
            };
            main.postDelayed(finishScan, SCAN_DURATION_MILLIS);
        } catch (SecurityException | IllegalStateException failure) {
            MeshDiagnostics.record(MeshDiagnostics.Event.SCAN_FAILED, diagnosticRadio);
            stopScan();
            status.setText(R.string.scan_access_unavailable);
        }
    }

    private void stopScan() {
        if (finishScan != null) {
            main.removeCallbacks(finishScan);
            finishScan = null;
        }
        ScanCallback callback = activeScan;
        BluetoothLeScanner scanningDevice = scanner;
        activeScan = null;
        scanner = null;
        if (scanningDevice != null && callback != null) {
            try {
                scanningDevice.stopScan(callback);
            } catch (SecurityException | IllegalStateException ignored) {
                // The scan has already lost Bluetooth access. Ignore any late callbacks.
            }
            MeshDiagnostics.record(MeshDiagnostics.Event.SCAN_FINISHED, diagnosticRadio);
        }
    }

    private void recordResults(ScanCallback callback, List<ScanResult> results) {
        if (activeScan != callback || dialog == null) {
            return;
        }
        try {
            for (ScanResult result : results) {
                addCandidate(result.getDevice(), result.getScanRecord(), false);
            }
        } catch (SecurityException failure) {
            stopScan();
            status.setText(R.string.scan_access_revoked);
        }
    }

    private void addCandidate(BluetoothDevice device, ScanRecord advertisement, boolean bonded) {
        String address = device.getAddress();
        Candidate previous = candidates.get(address);
        String advertisedName = advertisement == null ? null : advertisement.getDeviceName();
        String name = previous != null ? previous.name : null;
        if (advertisedName != null && !advertisedName.trim().isEmpty()) {
            name = advertisedName;
        } else if (name == null) {
            try {
                name = device.getName();
            } catch (SecurityException failure) {
                stopScan();
                status.setText(R.string.scan_access_revoked);
                return;
            }
        }
        String displayName = name == null || name.trim().isEmpty() ? "Unnamed device" : name;
        boolean matches = matchesNetwork(displayName, advertisement)
                || (previous != null && previous.matches);
        boolean paired = bonded || (previous != null && previous.bonded);
        Candidate candidate = new Candidate(device, displayName, paired, matches);
        if (candidate.equals(previous)) {
            return;
        }
        if (previous == null && candidates.size() >= MAX_CANDIDATES) {
            if (!matches) {
                return;
            }
            // Keep room for likely radios even in a scan with many unrelated devices.
            String toRemove = candidates.entrySet().stream()
                    .filter(entry -> !entry.getValue().matches && !entry.getValue().bonded)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (toRemove == null) {
                return;
            }
            candidates.remove(toRemove);
        }
        candidates.put(address, candidate);
        refreshRows();
    }

    private boolean matchesNetwork(String name, ScanRecord advertisement) {
        if (name.toLowerCase(Locale.ROOT).contains(network.label().toLowerCase(Locale.ROOT))) {
            return true;
        }
        List<ParcelUuid> services = advertisement == null ? null : advertisement.getServiceUuids();
        ParcelUuid expected = network == MeshService.Network.MESHTASTIC
                ? MESHTASTIC_SERVICE : MESHCORE_SERVICE;
        return services != null && services.contains(expected);
    }

    private void refreshRows() {
        boolean all = showAll.isChecked();
        rows.setNotifyOnChange(false);
        rows.clear();
        for (Candidate candidate : candidates.values()) {
            if (candidate.matches || all) {
                rows.add(candidate);
            }
        }
        rows.notifyDataSetChanged();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private record Candidate(BluetoothDevice device, String name, boolean bonded, boolean matches) {}
}
