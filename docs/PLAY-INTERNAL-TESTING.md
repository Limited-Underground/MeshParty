# MeshParty internal testing on Google Play

| Play Console field | Value |
| --- | --- |
| App name | MeshParty |
| Public developer name | Limited Underground |
| Account type | Personal |
| Package name | `com.limitedunderground.meshparty` |
| Internal test release | 0.1.5, version code 6 |
| Target and minimum Android | Target API 36, minimum API 31 (Android 12) |
| Track | Internal testing |
| Android App Bundle | `app/build/outputs/bundle/release/app-release.aab` by default (unsigned) |

## 1. Set up the publisher and app

1. Sign in to [Google Play Console](https://play.google.com/console/signup) with the Google account that will own MeshParty. Select **Personal** and enter **Limited Underground** as the public developer name. The account owner links the personal payments profile, completes Google's identity and contact checks, reviews the agreements, and pays the one-time US$25 registration fee. [Account setup](https://support.google.com/googleplay/android-developer/answer/6112435) · [Personal account information](https://support.google.com/googleplay/android-developer/answer/13628312)
2. Select **Create app**, then enter **MeshParty**, `com.limitedunderground.meshparty`, and the default language. Select **App** and choose the intended pricing. Use `admin@limitedunderground.com` as the initial support contact. The account owner reviews and accepts the declarations and Play App Signing terms shown by Google. [Create an app](https://support.google.com/googleplay/android-developer/answer/9859152)
3. If the existing debug install triggers a package ownership prompt, follow the verification instructions in Play Console. Keep the prior debug signing material available until registration is resolved. The package name is permanent after the first artifact upload. [Package registration](https://developer.android.com/developer-verification/guides/google-play-console) · [Internal testing](https://support.google.com/googleplay/android-developer/answer/9845334)

If Google says an individual payments profile already exists for your country, use **Select a payments profile** in the signup dialog. If the displayed name or address is outdated, first update it in [Google Payments Settings](https://payments.google.com/gp/w/home/settings), then return to Play Console and select that profile. [Manage an individual payments profile](https://support.google.com/faqs/answer/7644003)

## 2. Upload and invite testers

1. Open **Test and release → Testing → Internal testing → Create new release**. For Play App Signing, select the Google-generated app signing key. Upload the signed `.aab`. Google uses the separate upload key to authenticate bundles and its app signing key to sign the APKs delivered to testers. If Play asks for an upload certificate, use the public `.pem`. [Release setup](https://support.google.com/googleplay/android-developer/answer/9859348) · [Play App Signing](https://developer.android.com/studio/publish/app-signing)
2. Save and review the release. Resolve any blocking items in the Console, then roll it out to the **Internal testing** track.
3. In **Testers**, create or select an email list, add the Google accounts testers actually use in Play, and provide a feedback email or URL. Save the list and copy the opt-in link when it becomes available. Testers open the link, opt in, and install from Google Play. The first link can take several hours to become available. An internal test supports up to 100 invited testers. [Invite testers](https://support.google.com/googleplay/android-developer/answer/9845334)

The maintainer signs bundles using owner-controlled signing material. See [Play App Signing](https://developer.android.com/studio/publish/app-signing) for upload key management and recovery.

To build a bundle, follow the SDK setup in the [README](../README.md). After updating the version code for the intended release, use your approved reporting endpoint and build the unsigned bundle:

~~~sh
./gradlew -PmeshPartyReportUrl=https://reports.example.com/v1/reports :app:bundleRelease :app:lintRelease
~~~

On Windows, use `.\gradlew.bat`. The app needs a configured reporting endpoint for Play testing of abuse reports.

## 3. Complete App content

- **Privacy policy.** Enter the [public MeshParty privacy policy URL](https://limitedunderground.com/app-policies/radio-messaging) in Play Console. The public page describes direct abuse reporting and retains the disclosure for 0.1.4’s email-based reporting. The [in-app policy source](../app/src/main/assets/privacy-policy.html) is packaged into each new build. [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- **Data safety.** An app exclusively active on the internal track is exempt from the form. Complete it before moving to closed, open, or production distribution. The phone retains recent messages locally and sends chat content and routing information through the selected radio. When a user submits an abuse report, disclose the selected report details received by the support service as described in the privacy policy. Support diagnostics export remains user-initiated. [Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469)
- **Foreground service.** Under **App content**, declare **Connected device → Continuous data transfer to an external device**. The service starts when the user connects a radio and keeps Bluetooth sessions active so the app can receive messages while the screen is off. Deferring the service delays connection and messaging. Interrupting it can drop the radio links and delay reception until they reconnect. Provide a video link showing in-app radio selection and pairing, connection, a test message, and the user control to disconnect. [Connected-device declaration](https://support.google.com/googleplay/android-developer/answer/13392821)
- **App access.** Explain that the app has no sign-in, and that live messaging requires a compatible Bluetooth Meshtastic radio or MeshCore Bluetooth Companion radio. Tell reviewers to open **Radios**, choose the matching device, grant **Nearby devices**, and enter the PIN displayed by the radio if Android requests one. A second radio lets reviewers test the two connections together. [App review instructions](https://support.google.com/googleplay/android-developer/answer/9859455)
- **Chat safety.** Explain that users must accept the chat rules to write or send messages. The received-message menu offers reporting and the available sender block. In MeshCore channels, the block matches the displayed name on the same radio and channel. Blocking and muting are local and reversible in **Settings → Chat safety**. The in-app report form shows the evidence before submission and returns a receipt from the support service. Review incoming reports and act on issues within MeshParty's control. Follow the [reporting service configuration](../report-service/README.md) before building for Play. Answer the content rating questions based on the actual direct and channel messaging features. [User-generated content policy](https://support.google.com/googleplay/android-developer/answer/9876937)

Suggested listing wording

> MeshParty by Limited Underground puts MeshCore and Meshtastic conversations in one Android inbox. Connect both compatible radios over Bluetooth and choose which radio sends each message. MeshParty is independent and encourages use of the official MeshCore and Meshtastic apps.

## 4. Verify the Play-installed build

For the first install, use a test device without the existing sideloaded debug build, or keep the debug installation and its history until you choose how to transition. The debug APK has the same package name and a different signing key. Android requires a matching signing identity for an in-place update. Uninstalling the debug app removes its local history and settings. [Android signing behavior](https://developer.android.com/guide/app-bundle/faq)

Play installs skip the unknown-source setup used for sideloading. Play Protect still runs its usual checks. Testers should expect Android's **Nearby devices** prompt, a radio PIN if pairing requires one, and an optional notifications prompt. [Play Protect](https://support.google.com/googleplay/answer/2812853) · [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)

On the Play-installed build, test startup, Nearby devices permission, first-time PIN pairing, both radios connected together, sending and receiving on each network, screen-off reception, and disconnecting or reconnecting one radio while the other stays online. Use designated test channels or contacts and include the phone model, Android version, and radio firmware in feedback.

Verify the chat rules gate on a fresh install and that acceptance survives restart. With test traffic, block an identifiable sender and a MeshCore channel sender by displayed name. Check that matching messages and alerts are hidden and another sender is unaffected. Verify that a canceled report sends nothing. Submit one fictional message report and one fictional sender report. Confirm the app shows receipts, the service stores the reviewed evidence, and retrying the same submission creates one entry.

After a connection or send test, choose **Settings → Diagnostics → Export diagnostics** and save the `.txt` file using Android’s save dialog. Open it and check the app version, Android API level, elapsed event timing, and technical statuses. Check that any test message, contact or channel name, radio address, PIN, and raw traffic are absent. Testers can choose whether to share the file with Limited Underground support. **Clear diagnostics** removes the app’s saved events after confirmation. Testers can delete exported copies at the location where they saved them.

## Give feedback during testing

- Starting with MeshParty 0.1.6, tap **Settings → Send feedback** or **Feedback** at the top of the app. Pick a kind and write a note. Leave **Include app and phone details** on if you want to share the listed details, or turn it off. Tap **Send**, then review and send the draft in your email app. You can also use Google Play’s private feedback for MeshParty with the account that joined the test.
- During the closed test, stay opted in for at least **14 continuous days**. Leaving and rejoining starts the count again.

## Later changes

Before a public Play release, a new Personal account needs at least 12 testers opted in to a closed test continuously for 14 days, followed by an application for production access. The internal test is available separately. [Personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)

If Limited Underground later becomes a business, the owner can convert the verified Personal account to Organization in **Manage account → Account details → About you**. Google requires a verified organization website and a verified organization payments profile with the business's D‑U‑N‑S number. [Google's conversion guide](https://support.google.com/googleplay/android-developer/answer/16260648#convert-a-personal-developer-account-to-an-organization-account)
