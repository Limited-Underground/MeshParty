# MeshParty by Limited Underground

MeshParty is a standalone Android app for one Meshtastic radio and one MeshCore radio. It uses two independent Bluetooth LE connections and shows messages from both in one inbox. Each send button has its own channel or direct-message destination.

MeshParty is an independent alternative for using both networks from one screen. It is not intended to replace the official MeshCore or Meshtastic apps. We encourage you to use both official apps. [MeshCore Android app](https://play.google.com/store/apps/details?id=com.liamcottle.meshcore.android) · [Meshtastic downloads](https://meshtastic.org/downloads/#apps)

## Try the debug build

1. Use a phone with Android 12 or newer, a Bluetooth-enabled Meshtastic radio, and a radio running MeshCore Bluetooth Companion firmware.
2. In **Radios**, choose each radio separately and grant **Nearby devices** access when Android asks. Wait for both status indicators to read **Connected**.
3. In **Messages**, choose a destination beside each send button. Type a message and tap the button for the network you want. The **Reply** action selects the corresponding destination for an incoming message.
4. In **Settings**, choose the theme, default inbox, message alerts, Bluetooth reconnect, and optional channel-send confirmation. On Android 13 and newer, Android notification permission is required for message alerts.

## Build locally

Use JDK 17 and an Android SDK with Platform 36 installed. Set `ANDROID_HOME` to the SDK directory or set `sdk.dir` in an untracked `local.properties`.

~~~sh
./gradlew :app:assembleDebug :app:lintDebug
~~~

On Windows, run `.\gradlew.bat :app:assembleDebug :app:lintDebug` in PowerShell. The debug APK is written to `app/build/outputs/apk/debug/` by default. To keep generated output outside the source tree, pass `--project-cache-dir` and `-PmeshPartyBuildRoot` with paths on your workstation.

For Google Play testing, see [Google Play internal testing](docs/PLAY-INTERNAL-TESTING.md). For local reporting tests or to configure your own reporting service, see [report-service/README.md](report-service/README.md).

## Licence

MeshParty is available under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for the copyright notice.

Public contact [admin@limitedunderground.com](mailto:admin@limitedunderground.com).
