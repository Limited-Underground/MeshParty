# Changelog

Changes to the MeshParty Android app, newest first. Each version that reaches Google Play also gets a [GitHub release](https://github.com/Limited-Underground/MeshParty/releases) tagged on the commit it was built from.

## 0.2.3 (version code 13), in progress

- Each message you send shows how far it got: Sending…, Sent, Delivered ✓ or Not delivered. Direct messages reach Delivered when the recipient confirms (Meshtastic routing acknowledgement, MeshCore send confirmation); a channel message is Sent once the radio took it. A message with no confirmation after 3 minutes, or still waiting when the radio disconnects, shows Not delivered.
- **Resend** on a message that was not delivered sends it again to the same destination; the new copy replaces the old one.

## 0.2.2 (version code 12), 2026-11-28

- Nodes list on the Radios screen, under each connected radio: every node the radio heard (Meshtastic) or keeps as a contact (MeshCore), most recently heard first. Each row shows the name, ID, hops (or Direct), signal (SNR, Meshtastic only) and when it was last heard; MeshCore rows also show the type (companion, repeater, room server, sensor). The first 8 show, with Show all for the rest.

## 0.2.1 (version code 11), 2026-11-28

- The chat rules open in a window when the app starts. They must be accepted once before using MeshParty; Close app leaves without accepting. The "Review chat rules to write a message" button in the message box is gone.
- After the rules are accepted, MeshParty asks once for Android notification permission, so message alerts work right away.
- When Android notifications are blocked, the alert switches in Settings show off and greyed out, with a note and the Allow button.
- Radio status words (Connected, Offline, Reconnecting…) follow a language change at once.
- A What's new window lists the changes in the current version series, newest first. It opens once after an update and from Settings › About MeshParty.

## 0.2.0 (version code 10), 2026-11-28

- MeshCore radio setup: pick your region's preset (USA / Canada, EU / UK, Australia, New Zealand), name your radio and announce it nearby or across the mesh.
- Ten languages: English, Español, Italiano, Français, Deutsch, Hrvatski, Srpski, Српски, Русский and Українська, in Settings › Language. The translations are first drafts.

## 0.1.8 (version code 9), 2026-11-25

- First-time Meshtastic radio setup: region, modem preset, long and short name, with a warning when the region is outside the radio's band.
- The app version shows in Settings, and the radio settings are explained more clearly.

## 0.1.7 (version code 8)

- Send feedback goes straight to Limited Underground over HTTPS and shows a receipt; the email draft stays as an option.

## 0.1.6 (version code 7), 2026-11-20

- Send feedback from the app as an email draft.
- Radio picker device details follow the phone's language.

## 0.1.5 (version code 6), 2026-11-20

- First public source: one Android inbox for a Meshtastic radio and a MeshCore radio.
