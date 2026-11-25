package com.limitedunderground.meshinbox;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limitedunderground.meshinbox.MeshService.BlockedSender;
import com.limitedunderground.meshinbox.MeshService.BlockedSenderName;
import com.limitedunderground.meshinbox.MeshService.Message;
import com.limitedunderground.meshinbox.MeshService.MutedChannel;
import com.limitedunderground.meshinbox.MeshService.Network;
import com.limitedunderground.meshinbox.MeshService.RadioState;
import com.limitedunderground.meshinbox.RadioProtocol.Destination;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class MainActivity extends Activity {
    private enum Screen { MESSAGES, RADIOS, SETTINGS }
    private record Target(String address, Destination destination) {}
    private record Route(Button pick, Button send) {}

    private enum ReportReason {
        HARASSMENT(R.string.report_reason_harassment),
        THREAT(R.string.report_reason_threat),
        HATE(R.string.report_reason_hate),
        SEXUAL(R.string.report_reason_sexual),
        SPAM(R.string.report_reason_spam),
        ILLEGAL(R.string.report_reason_illegal),
        PRIVACY(R.string.report_reason_privacy),
        OTHER(R.string.report_reason_other);

        final int label;

        ReportReason(int label) {
            this.label = label;
        }

        String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final class ReportReview {
        final Message message;
        final boolean reportSender;
        final ReportReason reason;
        final String id = UUID.randomUUID().toString();
        boolean includeExtra;
        boolean submitting;
        AbuseReports.Result result;
        Runnable update;

        ReportReview(Message message, boolean reportSender, ReportReason reason) {
            this.message = message;
            this.reportSender = reportSender;
            this.reason = reason;
        }
    }

    private static final int BLUETOOTH_PERMISSIONS = 1;
    private static final int ENABLE_BLUETOOTH = 2;
    private static final int NOTIFICATION_PERMISSION = 3;
    private static final int EXPORT_DIAGNOSTICS = 4;
    private static final String ABOUT_MESHTASTIC = "https://meshtastic.org/downloads/#apps";
    private static final String ABOUT_MESHCORE = "https://play.google.com/store/apps/details?id=com.liamcottle.meshcore.android";
    private static final DateFormat MESSAGE_DATE = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    private final EnumMap<Network, TextView> statuses = new EnumMap<>(Network.class);
    private final EnumMap<Network, Target> targets = new EnumMap<>(Network.class);
    private final EnumMap<Network, Route> routes = new EnumMap<>(Network.class);
    private final Runnable onServiceChanged = this::refresh;
    private final ServiceConnection binding = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MeshService.LocalBinder) binder).service();
            service.addListener(onServiceChanged);
            refresh();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            releaseService();
            refresh();
        }
    };

    private SharedPreferences preferences;
    private MeshService service;
    private boolean bound;
    private RadioPicker radioPicker;
    private Network pendingPicker;
    private ReportReview activeReport;
    private AlertDialog reportDialog;
    private AlertDialog feedbackDialog;
    private AlertDialog setupDialog;
    private Screen screen = Screen.MESSAGES;
    private String filter;
    private String draft = "";
    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout messages;
    private ScrollView feed;
    private LinearLayout radioCards;
    private EditText composer;
    private Button composerRules;
    private int canvas;
    private int surface;
    private int ink;
    private int muted;
    private int border;
    private int tint;
    private boolean dark;

    @Override protected void onCreate(Bundle state) {
        MeshDiagnostics.record(MeshDiagnostics.Event.ACTIVITY_START);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        String theme = preferences.getString("theme", "System");
        dark = "Dark".equals(theme) || ("System".equals(theme)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        filter = preferences.getString("inbox", "All");
        if (state != null) {
            screen = Screen.valueOf(state.getString("screen", Screen.MESSAGES.name()));
            filter = state.getString("filter", filter);
            draft = state.getString("draft", "");
            String choosing = state.getString("pendingPicker");
            if (choosing != null) pendingPicker = Network.valueOf(choosing);
            for (Network network : Network.values()) {
                String prefix = network.name();
                String id = state.getString(prefix + "_targetId");
                if (id != null) {
                    Destination destination = new Destination(id, state.getString(prefix + "_targetLabel"),
                            state.getBoolean(prefix + "_targetChannel"));
                    targets.put(network, new Target(state.getString(prefix + "_targetAddress"), destination));
                }
            }
        }
        canvas = color(dark ? "#101817" : "#f4f7f5");
        surface = color(dark ? "#192421" : "#ffffff");
        ink = color(dark ? "#eaf8f2" : "#142c27");
        muted = color(dark ? "#b0c3ba" : "#48645a");
        border = color(dark ? "#374840" : "#d4e2db");
        tint = color(dark ? "#2b4439" : "#dcece5");

        getWindow().setDecorFitsSystemWindows(false);
        root = vertical();
        root.setBackgroundColor(canvas);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // The root already has a window when Android dispatches its insets.
            WindowInsetsController bars = view.getWindowInsetsController();
            if (bars != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                bars.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
            }
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard));
            return insets;
        });
        root.setFocusableInTouchMode(true);
        setContentView(root);
        render();
        MeshDiagnostics.record(MeshDiagnostics.Event.ACTIVITY_READY);
        if (getLastNonConfigurationInstance() instanceof ReportReview review) showReportReview(review);
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, MeshService.class), binding, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (radioPicker != null) {
            radioPicker.close();
            radioPicker = null;
        }
        releaseService();
        if (bound) unbindService(binding);
        bound = false;
        super.onStop();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return activeReport;
    }

    @Override protected void onDestroy() {
        if (feedbackDialog != null) feedbackDialog.dismiss();
        if (setupDialog != null) setupDialog.dismiss();
        if (activeReport != null) activeReport.update = null;
        if (reportDialog != null) {
            reportDialog.setOnDismissListener(null);
            reportDialog.dismiss();
        }
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("screen", screen.name());
        state.putString("filter", filter);
        state.putString("draft", draft);
        if (pendingPicker != null) state.putString("pendingPicker", pendingPicker.name());
        for (Network network : targets.keySet()) {
            Target target = targets.get(network);
            String prefix = network.name();
            state.putString(prefix + "_targetId", target.destination().id());
            state.putString(prefix + "_targetLabel", target.destination().label());
            state.putBoolean(prefix + "_targetChannel", target.destination().channel());
            state.putString(prefix + "_targetAddress", target.address());
        }
        super.onSaveInstanceState(state);
    }

    private void releaseService() {
        if (service != null) service.removeListener(onServiceChanged);
        service = null;
    }

    private void render() {
        root.removeAllViews();
        statuses.clear();
        routes.clear();
        composer = null;
        composerRules = null;
        LinearLayout header = vertical();
        padding(header, 16, 10, 16, 0);
        root.addView(header);
        LinearLayout brand = horizontal();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_meshparty);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout titles = vertical();
        padding(titles, 11, 0, 0, 0);
        titles.addView(label(getString(R.string.app_name), 25, ink, true));
        TextView wordmark = label(getString(R.string.brand_name), 12, muted, true);
        titles.addView(wordmark);
        brand.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Button feedback = button(getString(R.string.feedback_shortcut), tint, ink);
        feedback.setContentDescription(getString(R.string.feedback_title));
        feedback.setOnClickListener(view -> showFeedback());
        brand.addView(feedback);
        header.addView(brand);

        LinearLayout overview = horizontal();
        for (Network network : Network.values()) {
            LinearLayout card = vertical();
            padding(card, 10, 6, 10, 6);
            card.setBackground(box(surface, border, 10));
            card.addView(label(network.label(), 13, networkInk(network), true));
            TextView status = label("Offline", 12, muted, false);
            card.addView(status);
            statuses.put(network, status);
            addEqual(overview, card, network == Network.MESHCORE ? 8 : 0);
        }
        header.addView(overview, withTop(10));

        LinearLayout navigation = horizontal();
        for (Screen tab : Screen.values()) {
            String title = switch (tab) {
                case MESSAGES -> "Messages";
                case RADIOS -> "Radios";
                case SETTINGS -> "Settings";
            };
            Button button = button(title, tab == screen ? tint : canvas, ink);
            button.setSelected(tab == screen);
            button.setOnClickListener(view -> {
                if (tab != screen) {
                    WindowInsetsController controller = root.getWindowInsetsController();
                    if (controller != null) controller.hide(WindowInsets.Type.ime());
                    screen = tab;
                    render();
                }
            });
            addEqual(navigation, button, tab == Screen.MESSAGES ? 0 : 4);
        }
        header.addView(navigation, withTop(7));
        content = vertical();
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (screen) {
            case MESSAGES -> buildMessages();
            case RADIOS -> buildRadios();
            case SETTINGS -> buildSettings();
        }
        refresh();
    }

    private void buildMessages() {
        LinearLayout filters = horizontal();
        padding(filters, 16, 5, 16, 4);
        for (String name : List.of("All", Network.MESHTASTIC.label(), Network.MESHCORE.label())) {
            Button button = button(name, name.equals(filter) ? tint : surface, ink);
            button.setSelected(name.equals(filter));
            button.setOnClickListener(view -> {
                if (!name.equals(filter)) {
                    filter = name;
                    render();
                }
            });
            addEqual(filters, button, name.equals("All") ? 0 : 6);
        }
        content.addView(filters);

        feed = new ScrollView(this);
        feed.setFillViewport(true);
        messages = vertical();
        padding(messages, 16, 4, 16, 9);
        feed.addView(messages);
        content.addView(feed, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout compose = vertical();
        padding(compose, 16, 8, 16, 10);
        compose.setBackgroundColor(surface);
        composer = new EditText(this);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.setSingleLine(false);
        composer.setMinLines(1);
        composer.setMaxLines(3);
        composer.setMinHeight(dp(46));
        composer.setTextSize(16);
        composer.setTextColor(ink);
        composer.setHintTextColor(muted);
        composer.setHint("Write a message");
        composer.setBackground(box(canvas, border, 10));
        padding(composer, 10, 7, 10, 7);
        composer.setText(draft);
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                draft = text.toString();
                refreshRoutes();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        compose.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        composerRules = button(getString(R.string.chat_rules_to_write), tint, ink);
        composerRules.setOnClickListener(view -> showChatRules());
        compose.addView(composerRules, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout columns = horizontal();
        for (Network network : Network.values()) {
            LinearLayout column = vertical();
            Button pick = button("Choose destination", canvas, muted);
            pick.setOnClickListener(view -> pickDestination(network));
            column.addView(pick, new LinearLayout.LayoutParams(-1, -2));
            Button send = button("Send via " + network.label(), networkFill(network), networkInk(network));
            send.setOnClickListener(view -> requestSend(network));
            column.addView(send, withTop(4));
            routes.put(network, new Route(pick, send));
            addEqual(columns, column, network == Network.MESHCORE ? 8 : 0);
        }
        compose.addView(columns, withTop(6));
        content.addView(compose);
    }

    private void renderFeed() {
        boolean follow = !feed.canScrollVertically(1);
        messages.removeAllViews();
        List<Message> history = service == null ? List.of() : service.messages();
        int visible = 0;
        for (Message message : history) {
            if (!filter.equals("All") && !filter.equals(message.network().label())) continue;
            visible++;
            LinearLayout card = vertical();
            padding(card, 10, 8, 10, 6);
            card.setBackground(box(surface, border, 11));
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView author = label(message.outgoing() ? "You" : message.author(), 14, ink, true);
            row.addView(author, new LinearLayout.LayoutParams(0, -2, 1));
            TextView time = label(MESSAGE_DATE.format(new Date(message.timestampMillis())), 11, muted, false);
            row.addView(time);
            card.addView(row);
            TextView body = label(message.body(), 15, ink, false);
            body.setTextIsSelectable(true);
            card.addView(body, withTop(3));
            Destination target = message.replyTo();
            String context = message.network().label();
            if (target != null) context += " · " + destinationLabel(target);
            if (message.outgoing()) context += " · Send requested";
            LinearLayout details = horizontal();
            details.setGravity(Gravity.CENTER_VERTICAL);
            details.addView(label(context, 12, networkInk(message.network()), true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            RadioState state = radioState(message.network());
            if (!message.outgoing() && target != null && state.ready()
                    && message.radioAddress() != null && Objects.equals(state.address(), message.radioAddress())
                    && state.destinations().contains(target)) {
                Button reply = button("Reply", networkFill(message.network()), networkInk(message.network()));
                reply.setContentDescription("Reply via " + message.network().label() + " to " + destinationLabel(target));
                reply.setOnClickListener(view -> chooseTarget(message.network(), state.address(), target));
                details.addView(reply);
            }
            if (!message.outgoing()) {
                Button more = button(getString(R.string.message_options), canvas, ink);
                more.setContentDescription(getString(R.string.message_options_a11y, message.author()));
                more.setOnClickListener(view -> showMessageActions(message));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.setMarginStart(dp(4));
                details.addView(more, params);
            }
            card.addView(details, withTop(4));
            messages.addView(card, withTop(6));
        }
        if (visible == 0) {
            TextView empty = label("No messages yet", 17, ink, true);
            messages.addView(empty, withTop(18));
            messages.addView(label("Connect a radio to see messages here.", 14, muted, false), withTop(5));
        }
        ScrollView current = feed;
        if (follow) current.post(() -> current.scrollTo(0, messages.getHeight()));
    }

    private void refreshRoutes() {
        boolean canWrite = service != null && service.chatRulesAccepted();
        if (composer != null && composerRules != null) {
            composer.setVisibility(canWrite ? View.VISIBLE : View.GONE);
            composerRules.setVisibility(canWrite ? View.GONE : View.VISIBLE);
        }
        for (Network network : routes.keySet()) {
            Route controls = routes.get(network);
            RadioState state = radioState(network);
            Target target = currentTarget(network, state);
            String heading = target == null ? "Choose destination" : destinationLabel(target.destination());
            controls.pick().setText(heading);
            controls.pick().setContentDescription(network.label() + " destination · " + heading);
            controls.pick().setEnabled(state.ready() && !state.destinations().isEmpty());
            controls.send().setText(state.ready() ? "Send via " + network.label() : network.label() + " unavailable");
            controls.send().setEnabled(canWrite && target != null && !draft.trim().isEmpty());
        }
    }

    private void pickDestination(Network network) {
        RadioState state = radioState(network);
        if (!state.ready() || state.destinations().isEmpty()) return;
        List<Destination> items = state.destinations();
        CharSequence[] names = items.stream().map(MainActivity::destinationLabel).toArray(CharSequence[]::new);
        new AlertDialog.Builder(this).setTitle(network.label() + " destination")
                .setItems(names, (dialog, position) -> chooseTarget(network, state.address(), items.get(position)))
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseTarget(Network network, String address, Destination destination) {
        RadioState state = radioState(network);
        if (!state.ready() || !Objects.equals(state.address(), address) || !state.destinations().contains(destination)) {
            notifyUser("That destination is unavailable. Choose a destination again.");
            return;
        }
        targets.put(network, new Target(address, destination));
        refreshRoutes();
    }

    private Target currentTarget(Network network, RadioState state) {
        Target target = targets.get(network);
        if (target == null || !state.ready() || !Objects.equals(target.address(), state.address())
                || !state.destinations().contains(target.destination())) return null;
        return target;
    }

    private void requestSend(Network network) {
        Target target = currentTarget(network, radioState(network));
        String text = draft;
        if (service == null || target == null || text.trim().isEmpty()) return;
        if (!service.chatRulesAccepted()) {
            showChatRules();
            return;
        }
        if (target.destination().channel() && preferences.getBoolean("confirm_channel", false)) {
            new AlertDialog.Builder(this).setTitle("Send to " + destinationLabel(target.destination()) + "?")
                    .setMessage(network.label()).setNegativeButton("Cancel", null)
                    .setPositiveButton("Send", (dialog, which) -> send(network, target, text)).show();
        } else {
            send(network, target, text);
        }
    }

    private void send(Network network, Target target, String text) {
        if (service == null || !target.equals(currentTarget(network, radioState(network)))) {
            notifyUser("That destination is unavailable. Your draft is still here.");
            return;
        }
        String error = service.send(network, target.destination(), text);
        if (error != null) {
            notifyUser(error);
            return;
        }
        if (draft.equals(text) && composer != null) composer.setText("");
        if (!filter.equals("All") && !filter.equals(network.label())) {
            filter = "All";
            render();
        }
    }

    private void buildRadios() {
        ScrollView scroller = new ScrollView(this);
        radioCards = vertical();
        padding(radioCards, 16, 16, 16, 16);
        scroller.addView(radioCards);
        content.addView(scroller);
    }

    private void renderRadioCards() {
        radioCards.removeAllViews();
        radioCards.addView(label("Bluetooth radios", 19, ink, true));
        for (Network network : Network.values()) {
            RadioState state = radioState(network);
            LinearLayout card = vertical();
            padding(card, 12, 10, 12, 10);
            card.setBackground(box(surface, border, 12));
            card.addView(label(network.label(), 16, networkInk(network), true));
            String device = state.name() == null || state.name().trim().isEmpty() ? "Choose your radio" : state.name();
            card.addView(label(device + " · " + state.status(), 13, muted, false), withTop(4));
            String notice = service == null ? null : service.notice(network);
            if (notice != null && !notice.trim().isEmpty()) card.addView(label(notice, 13, ink, false), withTop(5));
            Button action = button(state.active() ? "Disconnect" : "Choose radio", networkFill(network), networkInk(network));
            action.setContentDescription((state.active() ? "Disconnect " : "Choose radio for ") + network.label());
            action.setEnabled(service != null);
            action.setOnClickListener(view -> {
                if (service == null) return;
                if (state.active()) service.disconnect(network);
                else beginRadioSelection(network);
            });
            card.addView(action, withTop(8));
            MeshtasticSetup.Current setup = network == Network.MESHTASTIC && service != null && state.ready()
                    ? service.meshtasticSetup() : null;
            if (setup != null) {
                boolean noticeShown = notice != null && !notice.trim().isEmpty();
                if (!setup.regionSet() && !noticeShown) {
                    card.addView(label(getString(R.string.setup_region_missing), 13, ink, true), withTop(6));
                }
                Button configure = button(getString(setup.regionSet() ? R.string.setup_button_change
                        : R.string.setup_button), tint, ink);
                configure.setEnabled(!service.setupInProgress(network));
                configure.setOnClickListener(view -> showRadioSetup());
                card.addView(configure, withTop(4));
            }
            radioCards.addView(card, withTop(10));
        }
        radioCards.addView(label("Use a Bluetooth-enabled Meshtastic radio and a radio running MeshCore Bluetooth Companion firmware.",
                13, muted, false), withTop(14));
    }

    private void beginRadioSelection(Network network) {
        pendingPicker = network;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                    BLUETOOTH_PERMISSIONS);
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            pendingPicker = null;
            notifyUser("Bluetooth is unavailable on this phone.");
        } else if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH);
        } else {
            showRadioPicker();
        }
    }

    private void showRadioPicker() {
        Network network = pendingPicker;
        pendingPicker = null;
        if (network == null) return;
        if (radioPicker != null) radioPicker.close();
        radioPicker = new RadioPicker(this, network, device -> {
            if (service == null) {
                notifyUser("Radio service is starting. Choose the radio again.");
                return;
            }
            service.connect(network, device);
        });
        radioPicker.show();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NOTIFICATION_PERMISSION && screen == Screen.SETTINGS) {
            render();
            return;
        }
        if (request != BLUETOOTH_PERMISSIONS || pendingPicker == null) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            beginRadioSelection(pendingPicker);
        } else {
            pendingPicker = null;
            notifyUser("Allow Nearby devices in Android settings to connect a radio.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_DIAGNOSTICS && result == RESULT_OK && data != null && data.getData() != null) {
            writeDiagnosticReport(data.getData());
            return;
        }
        if (request != ENABLE_BLUETOOTH) return;
        if (result == RESULT_OK) showRadioPicker();
        else pendingPicker = null;
    }

    private void buildSettings() {
        ScrollView scroller = new ScrollView(this);
        LinearLayout inner = vertical();
        padding(inner, 16, 12, 16, 16);
        scroller.addView(inner);
        content.addView(scroller);

        TextView version = label(getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                15, ink, true);
        inner.addView(version);
        Button feedback = button(getString(R.string.feedback_title), tint, ink);
        feedback.setOnClickListener(view -> showFeedback());
        inner.addView(feedback, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout display = settingsGroup(inner, "Display and inbox");
        addChoice(display, "Appearance", "theme", "System", List.of("System", "Light", "Dark"), this::recreate);
        addChoice(display, "Default inbox", "inbox", "All", List.of("All", "Meshtastic", "MeshCore"), () -> {
            filter = preferences.getString("inbox", "All");
            render();
        });
        LinearLayout alerts = settingsGroup(inner, "Message alerts");
        addSwitch(alerts, "Meshtastic alerts", "alert_meshtastic", true);
        addSwitch(alerts, "MeshCore alerts", "alert_meshcore", true);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Button permission = button("Allow Android notifications", tint, ink);
            permission.setOnClickListener(view -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION));
            alerts.addView(permission, withTop(3));
        }
        LinearLayout link = settingsGroup(inner, "Bluetooth");
        addSwitch(link, "Reconnect after a connection drops", "reconnect", true);
        LinearLayout sending = settingsGroup(inner, "Sending");
        addSwitch(sending, "Confirm channel sends", "confirm_channel", false);

        LinearLayout safety = settingsGroup(inner, getString(R.string.chat_safety));
        safety.addView(label(getString(R.string.chat_safety_summary), 14, ink, false));
        Button rules = button(getString(R.string.chat_rules), tint, ink);
        rules.setOnClickListener(view -> showChatRules());
        safety.addView(rules, withTop(7));
        Button blocked = button(getString(R.string.blocked_senders), surface, ink);
        blocked.setOnClickListener(view -> showBlockedSenders());
        safety.addView(blocked, withTop(4));
        Button mutedChannels = button(getString(R.string.muted_channels), surface, ink);
        mutedChannels.setOnClickListener(view -> showMutedChannels());
        safety.addView(mutedChannels, withTop(4));

        LinearLayout diagnostics = settingsGroup(inner, getString(R.string.diagnostics));
        diagnostics.addView(label(getString(R.string.diagnostics_summary), 14, ink, false));
        Button export = button(getString(R.string.export_diagnostics), tint, ink);
        export.setOnClickListener(view -> chooseDiagnosticFile());
        diagnostics.addView(export, withTop(7));
        Button clear = button(getString(R.string.clear_diagnostics), surface, ink);
        clear.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.clear_diagnostics)
                .setMessage(R.string.clear_diagnostics_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_diagnostics_action, (dialog, action) -> {
                    MeshDiagnostics.clear();
                    notifyUser(getString(R.string.diagnostics_cleared));
                }).show());
        diagnostics.addView(clear, withTop(4));

        LinearLayout about = settingsGroup(inner, "About MeshParty");
        about.addView(label(getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                14, ink, true));
        about.addView(label(getString(R.string.independent_app), 14, ink, false), withTop(4));
        Button privacy = button(getString(R.string.privacy_policy), tint, ink);
        privacy.setOnClickListener(view -> showPrivacyPolicy());
        about.addView(privacy, withTop(7));
        Button meshtastic = button("Official Meshtastic app", networkFill(Network.MESHTASTIC), networkInk(Network.MESHTASTIC));
        meshtastic.setOnClickListener(view -> openWebsite(ABOUT_MESHTASTIC));
        about.addView(meshtastic, withTop(4));
        Button meshcore = button("Official MeshCore app", networkFill(Network.MESHCORE), networkInk(Network.MESHCORE));
        meshcore.setOnClickListener(view -> openWebsite(ABOUT_MESHCORE));
        about.addView(meshcore, withTop(4));
    }

    private void showRadioSetup() {
        if (setupDialog != null || service == null) return;
        MeshtasticSetup.Current setup = service.meshtasticSetup();
        if (setup == null) {
            notifyUser("Connect the Meshtastic radio first.");
            return;
        }
        setupDialog = RadioSetupDialog.create(this, setup, request -> {
            String problem = service == null ? "Connect the Meshtastic radio first."
                    : service.applyMeshtasticSetup(request);
            if (problem == null) refresh();
            return problem;
        });
        setupDialog.setOnDismissListener(closed -> setupDialog = null);
        setupDialog.show();
    }

    private void showFeedback() {
        if (feedbackDialog != null) return;
        feedbackDialog = FeedbackDialog.create(this, () -> new FeedbackEmail.Details(
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.RELEASE,
                Build.MANUFACTURER, Build.MODEL,
                service != null && service.isConnected(Network.MESHTASTIC),
                service != null && service.isConnected(Network.MESHCORE)));
        feedbackDialog.setOnDismissListener(closed -> feedbackDialog = null);
        feedbackDialog.show();
    }

    private void showChatRules() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.chat_rules_version, MeshService.CHAT_RULES_VERSION))
                .setMessage(R.string.chat_rules_text);
        if (service == null || service.chatRulesAccepted()) {
            dialog.setPositiveButton(android.R.string.ok, null);
        } else {
            dialog.setNegativeButton(android.R.string.cancel, null);
            dialog.setPositiveButton(R.string.chat_rules_accept, (window, choice) -> {
                if (service == null) {
                    notifyUser(getString(R.string.chat_safety_unavailable));
                    return;
                }
                service.acceptChatRules();
                if (screen == Screen.MESSAGES) refreshRoutes();
            });
        }
        dialog.show();
    }

    private void showMessageActions(Message message) {
        List<Integer> actions = new ArrayList<>();
        actions.add(R.string.report_message);
        if (message.canBlockSender() || message.canBlockSenderName()) actions.add(R.string.report_sender);
        if (message.canBlockSender()) actions.add(R.string.block_sender);
        else if (message.canBlockSenderName()) actions.add(R.string.block_channel_sender_name);
        if (message.canMuteChannel()) actions.add(R.string.mute_channel);
        CharSequence[] labels = actions.stream().map(this::getString).toArray(CharSequence[]::new);
        new AlertDialog.Builder(this)
                .setTitle(R.string.message_actions)
                .setItems(labels, (dialog, position) -> {
                    int action = actions.get(position);
                    if (action == R.string.block_sender) confirmBlockSender(message);
                    else if (action == R.string.block_channel_sender_name) confirmBlockSenderName(message);
                    else if (action == R.string.mute_channel) confirmMuteChannel(message);
                    else chooseReportReason(message, action == R.string.report_sender);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmBlockSender(Message message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.block_sender)
                .setMessage(getString(R.string.block_sender_confirm, message.author(), message.network().label()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.block_sender, (dialog, choice) -> {
                    if (service == null || !service.blockSender(message)) {
                        notifyUser(getString(R.string.chat_safety_unavailable));
                    }
                })
                .show();
    }

    private void confirmBlockSenderName(Message message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.block_channel_sender_name)
                .setMessage(getString(R.string.block_channel_sender_name_confirm,
                        message.author(), message.replyTo().label()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.block_sender, (dialog, choice) -> {
                    if (service == null || !service.blockSenderName(message)) {
                        notifyUser(getString(R.string.chat_safety_unavailable));
                    }
                })
                .show();
    }

    private void confirmMuteChannel(Message message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.mute_channel)
                .setMessage(getString(R.string.mute_channel_confirm,
                        message.replyTo().label(), message.network().label()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mute_channel, (dialog, choice) -> {
                    if (service == null || !service.muteChannel(message)) {
                        notifyUser(getString(R.string.chat_safety_unavailable));
                    }
                })
                .show();
    }

    private void showBlockedSenders() {
        if (service == null) {
            notifyUser(getString(R.string.chat_safety_unavailable));
            return;
        }
        List<BlockedSender> senders = service.blockedSenders();
        List<BlockedSenderName> names = service.blockedSenderNames();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.blocked_senders)
                .setNegativeButton(android.R.string.ok, null);
        if (senders.isEmpty() && names.isEmpty()) {
            dialog.setMessage(R.string.blocked_senders_empty);
        } else {
            List<CharSequence> labels = new ArrayList<>();
            for (BlockedSender sender : senders) {
                labels.add(getString(R.string.chat_safety_entry_label, sender.label(), sender.network().label()));
            }
            for (BlockedSenderName name : names) {
                labels.add(getString(R.string.blocked_sender_name_entry, name.label(), name.channelLabel()));
            }
            dialog.setItems(labels.toArray(new CharSequence[0]), (window, position) -> {
                if (position < senders.size()) confirmUnblockSender(senders.get(position));
                else confirmUnblockSenderName(names.get(position - senders.size()));
            });
        }
        dialog.show();
    }

    private void confirmUnblockSender(BlockedSender sender) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unblock_sender)
                .setMessage(getString(R.string.unblock_sender_confirm, sender.label(), sender.network().label()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.unblock_sender, (dialog, choice) -> {
                    if (service == null) notifyUser(getString(R.string.chat_safety_unavailable));
                    else service.unblockSender(sender);
                })
                .show();
    }

    private void confirmUnblockSenderName(BlockedSenderName sender) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unblock_sender)
                .setMessage(getString(R.string.unblock_channel_sender_name_confirm,
                        sender.label(), sender.channelLabel()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.unblock_sender, (dialog, choice) -> {
                    if (service == null) notifyUser(getString(R.string.chat_safety_unavailable));
                    else service.unblockSenderName(sender);
                })
                .show();
    }

    private void showMutedChannels() {
        if (service == null) {
            notifyUser(getString(R.string.chat_safety_unavailable));
            return;
        }
        List<MutedChannel> channels = service.mutedChannels();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.muted_channels)
                .setNegativeButton(android.R.string.ok, null);
        if (channels.isEmpty()) {
            dialog.setMessage(R.string.muted_channels_empty);
        } else {
            CharSequence[] labels = channels.stream()
                    .map(channel -> getString(R.string.chat_safety_entry_label,
                            channel.label(), channel.network().label()))
                    .toArray(CharSequence[]::new);
            dialog.setItems(labels, (window, position) -> confirmUnmuteChannel(channels.get(position)));
        }
        dialog.show();
    }

    private void confirmUnmuteChannel(MutedChannel channel) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unmute_channel)
                .setMessage(getString(R.string.unmute_channel_confirm, channel.label(), channel.network().label()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.unmute_channel, (dialog, choice) -> {
                    if (service == null) notifyUser(getString(R.string.chat_safety_unavailable));
                    else service.unmuteChannel(channel);
                })
                .show();
    }

    private void chooseReportReason(Message message, boolean reportSender) {
        if (!AbuseReports.isConfigured()) {
            notifyUser(getString(R.string.report_service_unconfigured));
            return;
        }
        ReportReason[] reasons = ReportReason.values();
        CharSequence[] labels = new CharSequence[reasons.length];
        for (int index = 0; index < reasons.length; index++) labels[index] = getString(reasons[index].label);
        new AlertDialog.Builder(this)
                .setTitle(R.string.report_reason_title)
                .setItems(labels, (dialog, position) ->
                        showReportReview(new ReportReview(message, reportSender, reasons[position])))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showReportReview(ReportReview review) {
        activeReport = review;
        Message message = review.message;
        String senderId = message.canBlockSender() ? message.senderId() : null;
        String senderName = senderId == null && message.canBlockSenderName() ? message.author() : null;
        String identity = senderId != null ? senderId : senderName;
        boolean hasIdentity = identity != null;
        LinearLayout details = vertical();
        padding(details, 24, 8, 24, 4);
        details.addView(label(getString(R.string.report_review_summary), 16, ink, false));
        int type = review.reportSender ? R.string.report_sender : R.string.report_message;
        details.addView(label(getString(R.string.report_review_type, getString(type),
                getString(review.reason.label), message.network().label()), 14, ink, true), withTop(8));
        CheckBox includeExtra = new CheckBox(this);
        if (review.reportSender) {
            int required = senderId != null ? R.string.report_sender_id : R.string.report_sender_name;
            details.addView(label(getString(required, identity), 14, ink, false), withTop(8));
            includeExtra.setText(getString(R.string.report_extra_message, message.body()));
        } else {
            details.addView(label(getString(R.string.report_selected_message, message.body()),
                    14, ink, false), withTop(8));
            int optional = senderId != null ? R.string.report_extra_sender_id : R.string.report_extra_sender_name;
            if (hasIdentity) includeExtra.setText(getString(optional, identity));
        }
        if (review.reportSender || hasIdentity) details.addView(includeExtra, withTop(8));
        includeExtra.setChecked(review.includeExtra);
        includeExtra.setOnCheckedChangeListener((view, checked) -> review.includeExtra = checked);
        TextView status = label("", 14, ink, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        details.addView(status, withTop(8));
        ScrollView scroller = new ScrollView(this);
        scroller.addView(details);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.report_review_title)
                .setView(scroller)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.report_submit, null)
                .create();
        reportDialog = dialog;
        review.update = () -> updateReportReview(review, dialog, includeExtra, status);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(window -> {
            review.update = null;
            if (activeReport == review) activeReport = null;
            if (reportDialog == dialog) reportDialog = null;
        });
        dialog.setOnShowListener(window -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> submitReport(review, senderId, senderName));
            review.update.run();
        });
        dialog.show();
    }

    private void updateReportReview(ReportReview review, AlertDialog dialog, CheckBox includeExtra, TextView status) {
        AbuseReports.Result result = review.result;
        if (result != null && result.status() == AbuseReports.Status.SENT
                && result.receipt() != null && !result.receipt().isEmpty()) {
            dialog.dismiss();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.report_sent_title)
                    .setMessage(getString(R.string.report_sent_receipt, result.receipt()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        dialog.setCancelable(!review.submitting);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!review.submitting);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setEnabled(!review.submitting && (result == null || result.status() != AbuseReports.Status.REJECTED));
        positive.setText(result == null ? R.string.report_submit : R.string.report_retry);
        includeExtra.setEnabled(!review.submitting && result == null);
        int feedback = 0;
        if (review.submitting) feedback = R.string.report_sending;
        else if (result != null) {
            feedback = switch (result.status()) {
                case UNAVAILABLE -> R.string.report_service_unavailable;
                case OFFLINE -> R.string.report_offline;
                case REJECTED -> R.string.report_rejected;
                case FAILED, SENT -> R.string.report_delivery_unconfirmed;
            };
        }
        status.setVisibility(feedback == 0 ? View.GONE : View.VISIBLE);
        if (feedback != 0) status.setText(feedback);
    }

    private void submitReport(ReportReview review, String senderId, String senderName) {
        if (review.submitting) return;
        boolean sendIdentity = review.reportSender || review.includeExtra;
        String selectedMessage = !review.reportSender || review.includeExtra ? review.message.body() : null;
        String kind = review.reportSender ? "sender" : "message";
        String network = review.message.network().name().toLowerCase(Locale.ROOT);
        review.submitting = true;
        review.result = null;
        review.update.run();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            AbuseReports.Result result;
            try {
                result = AbuseReports.submit(review.id, kind, review.reason.code(), network,
                        selectedMessage, sendIdentity ? senderId : null, sendIdentity ? senderName : null);
            } catch (RuntimeException error) {
                result = new AbuseReports.Result(AbuseReports.Status.FAILED, null);
            }
            AbuseReports.Result outcome = result;
            main.post(() -> {
                review.submitting = false;
                review.result = outcome;
                if (review.update != null) review.update.run();
            });
        }, "MeshParty abuse report").start();
    }

    private void chooseDiagnosticFile() {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "MeshParty-diagnostics.txt");
        try {
            startActivityForResult(save, EXPORT_DIAGNOSTICS);
        } catch (android.content.ActivityNotFoundException unavailable) {
            notifyUser(getString(R.string.diagnostics_picker_unavailable));
        }
    }

    private void writeDiagnosticReport(Uri destination) {
        if (!ContentResolver.SCHEME_CONTENT.equals(destination.getScheme())) {
            notifyUser(getString(R.string.diagnostics_save_failed));
            return;
        }
        Context app = getApplicationContext();
        byte[] report = MeshDiagnostics.exportReport(app).getBytes(StandardCharsets.UTF_8);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int message;
            try (OutputStream output = app.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("No output stream");
                output.write(report);
                message = R.string.diagnostics_saved;
            } catch (IOException | RuntimeException error) {
                message = R.string.diagnostics_save_failed;
            }
            int result = message;
            main.post(() -> Toast.makeText(app, result, Toast.LENGTH_LONG).show());
        }, "MeshParty diagnostics export").start();
    }

    private void showPrivacyPolicy() {
        StringBuilder html = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(
                getAssets().open("privacy-policy.html"), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) html.append(buffer, 0, count);
        } catch (IOException error) {
            notifyUser(getString(R.string.privacy_policy_unavailable));
            return;
        }

        WebView policy = new WebView(this);
        policy.getSettings().setBlockNetworkLoads(true);
        policy.getSettings().setAllowFileAccess(false);
        policy.getSettings().setAllowContentAccess(false);
        policy.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri destination = request.getUrl();
                if (request.isForMainFrame() && request.hasGesture()
                        && ("https".equals(destination.getScheme()) || "mailto".equals(destination.getScheme()))) {
                    openWebsite(destination.toString());
                }
                return true;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy)
                .setView(policy)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(closed -> policy.destroy());
        policy.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);
        dialog.show();
    }

    private LinearLayout settingsGroup(LinearLayout parent, String heading) {
        parent.addView(label(heading, 13, muted, true), withTop(parent.getChildCount() == 0 ? 0 : 12));
        LinearLayout group = vertical();
        padding(group, 10, 6, 10, 6);
        group.setBackground(box(surface, border, 11));
        parent.addView(group, withTop(5));
        return group;
    }

    private void addChoice(LinearLayout group, String title, String key, String fallback,
                           List<String> options, Runnable changed) {
        String selected = preferences.getString(key, fallback);
        Button row = button(title + " · " + selected, surface, ink);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toArray(new String[0]), options.indexOf(selected), (dialog, which) -> {
                    preferences.edit().putString(key, options.get(which)).apply();
                    dialog.dismiss();
                    changed.run();
                }).setNegativeButton("Cancel", null).show());
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSwitch(LinearLayout group, String title, String key, boolean fallback) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(14);
        toggle.setTextColor(ink);
        toggle.setMinHeight(dp(46));
        toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((view, checked) -> preferences.edit().putBoolean(key, checked).apply());
        group.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
    }

    private void refresh() {
        for (Network network : statuses.keySet()) statuses.get(network).setText(radioState(network).status());
        if (screen == Screen.MESSAGES) {
            renderFeed();
            refreshRoutes();
        } else if (screen == Screen.RADIOS) {
            renderRadioCards();
        }
    }

    private RadioState radioState(Network network) {
        if (service != null) return service.state(network);
        return new RadioState(null, null, "Offline", false, false, List.of());
    }

    private static String destinationLabel(Destination destination) {
        return destination.channel() ? "# " + destination.label() : destination.label() + " · DM";
    }

    private Button button(String text, int fill, int foreground) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{muted, foreground}));
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(border), box(fill, 0, 9), null));
        padding(button, 6, 3, 6, 3);
        return button;
    }

    private TextView label(String text, int size, int foreground, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(foreground);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        return new LinearLayout(this);
    }

    private void addEqual(LinearLayout parent, View child, int start) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMarginStart(dp(start));
        parent.addView(child, params);
    }

    private LinearLayout.LayoutParams withTop(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        return params;
    }

    private void padding(View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    private GradientDrawable box(int background, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int color(String value) { return Color.parseColor(value); }

    private int networkFill(Network network) {
        return color(network == Network.MESHTASTIC ? (dark ? "#214d3e" : "#d6efe2")
                : (dark ? "#4a371d" : "#fae9cc"));
    }

    private int networkInk(Network network) {
        return color(network == Network.MESHTASTIC ? (dark ? "#b4f1d0" : "#095743")
                : (dark ? "#ffdc9f" : "#684211"));
    }

    private void openWebsite(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException unavailable) {
            notifyUser("No browser is available on this phone.");
        }
    }

    private void notifyUser(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
