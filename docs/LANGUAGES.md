# App languages

MeshParty's text is translated with ordinary Android string resources (D-197). The approach follows the MeshTrail app.

## Languages

| Language | Folder | Picker name | State |
|---|---|---|---|
| English (base) | `values` | English | Source text |
| Spanish | `values-es` | Español | Unreviewed draft |
| Italian | `values-it` | Italiano | Unreviewed draft |
| French | `values-fr` | Français | Unreviewed draft |
| German | `values-de` | Deutsch | Unreviewed draft |
| Croatian | `values-hr` | Hrvatski | Unreviewed draft |
| Serbian, Latin script | `values-b+sr+Latn` | Srpski (latinica) | Unreviewed draft |
| Serbian, Cyrillic script | `values-b+sr+Cyrl` | Српски (ћирилица) | Unreviewed draft, converted letter by letter from the Latin draft (D-196) |
| Russian | `values-ru` | Русский | Unreviewed draft |
| Ukrainian | `values-uk` | Українська | Unreviewed draft |

The list lives in three places that must agree: `AppLanguage.java`, `res/xml/locales_config.xml` and the `res/values-*` folders. `tests/host/LanguageResourcesTest.java` fails if they drift.

## Choosing a language

- Settings, Display and inbox, Language: System default or one of the languages above, each in its own name.
- Android 13 and later: the system stores the choice; the phone's own app settings also offer it (App language).
- Android 12: the app stores it and applies it when a screen or the radio service starts.
- Play bundles keep every language (`bundle.language.enableSplit = false`), so the picker always has them all.

## Adding or changing text

1. Put the English text in `res/values/strings.xml` and use `getString(R.string.…)` in screens and dialogs.
2. Classes without an Android `Context` (the radio protocols and setup models) use `Text.t("text_name", "English text", args…)`. The English stays in the code so the host tests run without Android; `LanguageResourcesTest` checks it is identical to `strings.xml`. Use one literal per call.
3. Add the same name to every `values-*/strings.xml`. Lint (`MissingTranslation`, `ExtraTranslation` and `MissingQuantity` are errors here) and `LanguageResourcesTest` both fail if one is missing or extra, or if a placeholder differs.
4. Counts use `<plurals>`. Russian and Ukrainian need `one`, `few`, `many` and `other`; Serbian and Croatian need `one`, `few` and `other`; Spanish, French and Italian also need `many`.
5. Escape apostrophes as `\'` (French and Italian use them often).
6. Names never translate: MeshParty, Meshtastic, MeshCore, Limited Underground, Bluetooth, LongFast and the like stay as they are.
7. Keep labels short. `LanguageLayoutTest` (Robolectric, `./gradlew :app:testDebugUnitTest`) renders the Messages, Radios and Settings screens, both radio setup forms and the language list in every language on a 360 dp phone. It fails if text is cut off, runs off the screen, or a tab or filter label wraps.

`tools/Get-AppHardcodedText.ps1` lists English still written directly in Java. It reports 0 after D-197.

## Kept in English on purpose

- Feedback and abuse reports sent to the Limited Underground service, and the email feedback draft, so reports stay readable (`FeedbackEmail`, `FeedbackSubmission`, `FeedbackClient`, `AbuseReports`).
- The diagnostics export (`MeshDiagnostics`).
- The embedded privacy policy (`assets/privacy-policy.html`), which must match the public policy.
- The `MeshCore sender` label for channel messages without a sender name, which the app also uses as a marker.

## Review state

Every non-English file starts with a comment whose first word is `UNREVIEWED:` or `REVIEWED:` (the test checks this). Drafts written by an AI stay `UNREVIEWED` until a native-speaker tester reads them in the app (D-194); then change the word and add the reviewer and date. Testers get unreviewed drafts; a language goes into a public release only after its review (D-195).
