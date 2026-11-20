package com.limitedunderground.meshinbox;

import android.content.SharedPreferences;

import com.limitedunderground.meshinbox.MeshService.BlockedSender;
import com.limitedunderground.meshinbox.MeshService.BlockedSenderName;
import com.limitedunderground.meshinbox.MeshService.Message;
import com.limitedunderground.meshinbox.MeshService.MutedChannel;
import com.limitedunderground.meshinbox.MeshService.Network;
import com.limitedunderground.meshinbox.RadioProtocol.Destination;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local filters for one inbox, with identity scoped to the radio protocol. */
final class ChatModeration {
    private static final String RULES = "chat_rules_version";
    private static final String BLOCKED = "blocked_senders";
    private static final String BLOCKED_NAMES = "blocked_sender_names";
    private static final String MUTED = "muted_channels";
    private static final Pattern MESHTASTIC_ID = Pattern.compile("node:[0-9a-f]{8}");
    private static final Pattern MESHTASTIC_DESTINATION =
            Pattern.compile("(node:[0-9a-f]{8})(?::[A-Za-z0-9_-]{43})?");
    private static final Pattern MESHCORE_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CHANNEL_ID = Pattern.compile("channel:[0-9]{1,3}");
    private static final Pattern RADIO_ADDRESS = Pattern.compile("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");

    private record SenderKey(Network network, String id) {}
    private record ChannelKey(Network network, String radioAddress, String id) {}
    private record ChannelSenderKey(String radioAddress, String channelId, String channelLabel,
                                    String normalizedName) {}

    private final SharedPreferences preferences;
    private final Map<SenderKey, BlockedSender> blocked = new LinkedHashMap<>();
    private final Map<ChannelSenderKey, BlockedSenderName> blockedNames = new LinkedHashMap<>();
    private final Map<ChannelKey, MutedChannel> muted = new LinkedHashMap<>();

    ChatModeration(SharedPreferences preferences) {
        this.preferences = preferences;
        JSONArray senders = load(BLOCKED);
        for (int index = 0; index < senders.length(); index++) {
            JSONObject row = senders.optJSONObject(index);
            if (row == null) continue;
            try {
                Network network = Network.valueOf(row.getString("network"));
                String id = senderId(network, row.getString("id"));
                if (id != null) {
                    blocked.put(new SenderKey(network, id),
                            new BlockedSender(network, id, row.getString("label")));
                }
            } catch (JSONException | IllegalArgumentException malformed) {
                // Preserve the other rules if one saved entry is malformed.
            }
        }

        JSONArray channels = load(MUTED);
        for (int index = 0; index < channels.length(); index++) {
            JSONObject row = channels.optJSONObject(index);
            if (row == null) continue;
            try {
                Network network = Network.valueOf(row.getString("network"));
                String address = row.getString("address");
                String id = row.getString("id");
                if (network == Network.MESHCORE && validChannel(address, id)) {
                    muted.put(new ChannelKey(network, address, id),
                            new MutedChannel(network, address, id, row.getString("label")));
                }
            } catch (JSONException | IllegalArgumentException malformed) {
                // Preserve the other rules if one saved entry is malformed.
            }
        }

        JSONArray names = load(BLOCKED_NAMES);
        for (int index = 0; index < names.length(); index++) {
            JSONObject row = names.optJSONObject(index);
            if (row == null) continue;
            try {
                String address = row.getString("address");
                String id = row.getString("id");
                String channelLabel = row.getString("channelLabel");
                String label = row.getString("label");
                ChannelSenderKey key = channelSenderKey(address, id, channelLabel, label);
                if (key != null) {
                    blockedNames.put(key, new BlockedSenderName(address, id, channelLabel, label));
                }
            } catch (JSONException malformed) {
                // Preserve the other rules if one saved entry is malformed.
            }
        }
    }

    boolean rulesAccepted() {
        return preferences.getInt(RULES, 0) == MeshService.CHAT_RULES_VERSION;
    }

    void acceptRules() {
        preferences.edit().putInt(RULES, MeshService.CHAT_RULES_VERSION).apply();
    }

    List<BlockedSender> blockedSenders() { return List.copyOf(blocked.values()); }

    List<BlockedSenderName> blockedSenderNames() { return List.copyOf(blockedNames.values()); }

    List<MutedChannel> mutedChannels() { return List.copyOf(muted.values()); }

    boolean block(Message message) {
        if (!message.canBlockSender()) return false;
        SenderKey key = new SenderKey(message.network(), message.senderId());
        if (blocked.containsKey(key)) return false;
        blocked.put(key, new BlockedSender(key.network(), key.id(),
                displayLabel(message.author(), "Unknown sender")));
        saveBlocked();
        return true;
    }

    boolean unblock(BlockedSender sender) {
        if (!blocked.remove(new SenderKey(sender.network(), sender.id()), sender)) return false;
        saveBlocked();
        return true;
    }

    boolean blockSenderName(Message message) {
        if (!message.canBlockSenderName()) return false;
        Destination target = message.replyTo();
        String label = message.author().trim();
        ChannelSenderKey key = channelSenderKey(message.radioAddress(), target.id(), target.label(), label);
        BlockedSenderName entry = new BlockedSenderName(message.radioAddress(), target.id(), target.label(), label);
        if (blockedNames.putIfAbsent(key, entry) != null) return false;
        saveBlockedNames();
        return true;
    }

    boolean unblockSenderName(BlockedSenderName sender) {
        ChannelSenderKey key = channelSenderKey(sender.radioAddress(), sender.channelId(),
                sender.channelLabel(), sender.label());
        if (!blockedNames.remove(key, sender)) return false;
        saveBlockedNames();
        return true;
    }

    boolean mute(Message message) {
        if (!message.canMuteChannel()) return false;
        Destination target = message.replyTo();
        ChannelKey key = new ChannelKey(message.network(), message.radioAddress(), target.id());
        if (muted.containsKey(key)) return false;
        muted.put(key, new MutedChannel(key.network(), key.radioAddress(), key.id(),
                displayLabel(target.label(), "Unknown channel")));
        saveMuted();
        return true;
    }

    boolean unmute(MutedChannel channel) {
        ChannelKey key = new ChannelKey(channel.network(), channel.radioAddress(), channel.id());
        if (!muted.remove(key, channel)) return false;
        saveMuted();
        return true;
    }

    boolean blocksDestination(Network network, Destination target) {
        return blocked.containsKey(new SenderKey(network, directSenderId(network, target)));
    }

    boolean hides(Message message) {
        return !message.outgoing() && hidesIncoming(message.network(), message.senderId(),
                message.replyTo(), message.radioAddress(), message.author());
    }

    boolean hidesIncoming(Network network, String id, Destination target, String radioAddress,
                          String author) {
        if (id != null && blocked.containsKey(new SenderKey(network, id))) return true;
        if (target == null || !target.channel()) return false;
        if (muted.containsKey(new ChannelKey(network, radioAddress, target.id()))) return true;
        return network == Network.MESHCORE && id == null && !blockedNames.isEmpty()
                && blockedNames.containsKey(channelSenderKey(radioAddress, target.id(),
                        target.label(), author));
    }

    static boolean canBlockSenderName(Message message) {
        Destination target = message.replyTo();
        return !message.outgoing() && message.network() == Network.MESHCORE
                && message.senderId() == null && target != null && target.channel()
                && channelSenderKey(message.radioAddress(), target.id(), target.label(),
                        message.author()) != null;
    }

    private static ChannelSenderKey channelSenderKey(String radioAddress, String channelId,
            String channelLabel, String author) {
        if (!validChannel(radioAddress, channelId) || channelLabel == null || channelLabel.trim().isEmpty()
                || author == null || author.length() > 128) return null;
        String name = Normalizer.normalize(author.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals(MeshCoreProtocol.UNKNOWN_CHANNEL_SENDER.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return new ChannelSenderKey(radioAddress.toUpperCase(Locale.ROOT), channelId, channelLabel, name);
    }

    static String senderId(Network network, String id) {
        if (id == null) return null;
        Pattern form = network == Network.MESHTASTIC ? MESHTASTIC_ID : MESHCORE_KEY;
        return form.matcher(id).matches() ? id : null;
    }

    static String directSenderId(Network network, Destination target) {
        if (target == null || target.channel() || target.id() == null) return null;
        if (network == Network.MESHCORE) return senderId(network, target.id());
        Matcher matcher = MESHTASTIC_DESTINATION.matcher(target.id());
        return matcher.matches() ? matcher.group(1) : null;
    }

    static boolean validChannel(String address, String id) {
        return address != null && RADIO_ADDRESS.matcher(address).matches()
                && id != null && CHANNEL_ID.matcher(id).matches();
    }

    private static String displayLabel(String label, String fallback) {
        return label == null || label.trim().isEmpty() ? fallback : label;
    }

    private JSONArray load(String key) {
        try {
            return new JSONArray(preferences.getString(key, "[]"));
        } catch (JSONException | ClassCastException malformed) {
            return new JSONArray();
        }
    }

    private void saveBlocked() {
        JSONArray rows = new JSONArray();
        try {
            for (BlockedSender sender : blocked.values()) {
                rows.put(new JSONObject().put("network", sender.network().name())
                        .put("id", sender.id()).put("label", sender.label()));
            }
        } catch (JSONException malformed) {
            throw new IllegalStateException(malformed);
        }
        preferences.edit().putString(BLOCKED, rows.toString()).apply();
    }

    private void saveBlockedNames() {
        JSONArray rows = new JSONArray();
        try {
            for (BlockedSenderName sender : blockedNames.values()) {
                rows.put(new JSONObject().put("address", sender.radioAddress())
                        .put("id", sender.channelId()).put("channelLabel", sender.channelLabel())
                        .put("label", sender.label()));
            }
        } catch (JSONException malformed) {
            throw new IllegalStateException(malformed);
        }
        preferences.edit().putString(BLOCKED_NAMES, rows.toString()).apply();
    }

    private void saveMuted() {
        JSONArray rows = new JSONArray();
        try {
            for (MutedChannel channel : muted.values()) {
                rows.put(new JSONObject().put("network", channel.network().name())
                        .put("address", channel.radioAddress()).put("id", channel.id())
                        .put("label", channel.label()));
            }
        } catch (JSONException malformed) {
            throw new IllegalStateException(malformed);
        }
        preferences.edit().putString(MUTED, rows.toString()).apply();
    }
}
