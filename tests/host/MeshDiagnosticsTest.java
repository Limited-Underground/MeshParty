package com.limitedunderground.meshinbox;

import com.limitedunderground.meshinbox.MeshDiagnostics.Entry;
import com.limitedunderground.meshinbox.MeshDiagnostics.Event;
import com.limitedunderground.meshinbox.MeshDiagnostics.History;
import com.limitedunderground.meshinbox.MeshDiagnostics.Radio;

public final class MeshDiagnosticsTest {
    public static void main(String[] args) {
        allowlistedEventsRoundTripAcrossSessions();
        malformedAndUnapprovedFieldsStayOutOfReport();
        historyRetainsOnlyTheLatestEvents();
        System.out.println("MeshParty diagnostic host checks passed");
    }

    private static void allowlistedEventsRoundTripAcrossSessions() {
        History first = new History();
        first.add(new Entry(Event.APP_STARTED, Radio.NONE, 0, -1));
        first.add(new Entry(Event.GATT_CONNECT_FAILED, Radio.MESHCORE, 310, 133));
        first.add(new Entry(Event.GATT_MTU, Radio.MESHTASTIC, 342, 247));

        History restarted = History.decode(first.encode());
        restarted.add(new Entry(Event.APP_STARTED, Radio.NONE, 0, -1));
        restarted.add(new Entry(Event.SCAN_FAILED, Radio.MESHCORE, 90, 2));
        restarted.add(new Entry(Event.GATT_TIMEOUT, Radio.MESHTASTIC, 454, 4));
        restarted.add(new Entry(Event.GATT_WRITE_FAILED, Radio.MESHCORE, 655, Integer.MAX_VALUE));
        restarted.add(new Entry(Event.RECONNECT_SCHEDULED, Radio.MESHTASTIC, 756, 4));

        String report = report(restarted);
        require(report.contains("Session 1\n+0 ms APP_STARTED"), "old session is retained");
        require(report.contains("+310 ms MESHCORE GATT_CONNECT_FAILED status=133"), "GATT status is retained");
        require(report.contains("+342 ms MESHTASTIC GATT_MTU mtu=247"), "MTU is retained");
        require(report.contains("Session 2\n+0 ms APP_STARTED"), "elapsed time resets across sessions");
        require(report.contains("+90 ms MESHCORE SCAN_FAILED status=2"), "scan errors are retained");
        require(report.contains("+454 ms MESHTASTIC GATT_TIMEOUT stage=subscription"), "timeout phase is rendered");
        require(report.contains("+655 ms MESHCORE GATT_WRITE_FAILED status=unknown"), "unknown status is labeled");
        require(report.contains("+756 ms MESHTASTIC RECONNECT_SCHEDULED delay_s=4"), "reconnect delay is retained");
    }

    private static void malformedAndUnapprovedFieldsStayOutOfReport() {
        String persisted = """
                GATT_CONNECTED|MESHTASTIC|40|-1
                secret message body|MESHTASTIC|20|-1
                GATT_CONNECTED|00:11:22:33:44:55|20|-1
                GATT_CONNECTED|MESHCORE|person@example.invalid|-1
                GATT_CONNECTED|MESHCORE|20|123456
                GATT_CONNECTED|MESHCORE|20|-1|secret channel
                GATT_CONNECT_FAILED|MESHCORE|30|847201
                GATT_MTU|MESHCORE|30|22
                GATT_TIMEOUT|MESHCORE|30|7
                GATT_CONNECTED|MESHCORE|9749578934|-1
                RECONNECT_SCHEDULED|MESHCORE|-5|4
                """;
        History restored = History.decode(persisted);
        String report = report(restored);
        require(report.contains("Retained events: 1/200"), "invalid rows are discarded on load");
        require(report.contains("+40 ms MESHTASTIC GATT_CONNECTED"), "valid rows survive malformed data");
        for (String example : new String[]{"secret", "00:11:22:33:44:55", "example.invalid", "123456", "847201", "9749578934"}) {
            require(!report.contains(example), "report does not echo unapproved preferences content");
        }
        require("0.1.3".equals(MeshDiagnostics.safeVersion("0.1.3")), "expected build version is retained");
        require("unknown".equals(MeshDiagnostics.safeVersion("0.1.3-person@example.invalid")), "arbitrary version text is dropped");
        require(report(History.decode("x".repeat(20001))).contains("Retained events: 0/200"), "oversized histories are discarded");
    }

    private static void historyRetainsOnlyTheLatestEvents() {
        History history = new History();
        int limit = MeshDiagnostics.HISTORY_LIMIT;
        for (int index = 0; index < limit + 10; index++) {
            history.add(new Entry(Event.GATT_CONNECTED, Radio.MESHCORE, index, -1));
        }
        String report = report(History.decode(history.encode()));
        require(report.contains("Retained events: 200/200"), "only the last 200 events are retained");
        require(report.contains("Session 1\n+10 ms "), "the oldest entry is evicted first");
        require(report.contains("+209 ms "), "the newest entry survives reload");
        require(!report.contains("+9 ms "), "the previous entry rolled off");
    }

    private static String report(History history) {
        StringBuilder report = new StringBuilder();
        history.appendReport(report);
        return report.toString();
    }

    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
