# Google-Play-Variante (Build-Schalter `-Pdistribution=play`)

Ohne Schalter entsteht wie bisher die GitHub-APK (eigener Updater, `REQUEST_INSTALL_PACKAGES`, Handy targetSdk 36, TV 35). CI, Release-Workflow und Update-Feeds ändern sich nicht.

Mit `-Pdistribution=play`:

| Bereich | GitHub (Standard) | Play |
|---|---|---|
| Updates | `core/src/github/…/UpdateChecker.kt`: Feed lesen, APK laden, System-Installer | `core/src/play/…/UpdateChecker.kt`: Play In-App-Updates (`com.google.android.play:app-update:2.1.0`), flexibel, sofort bei Priorität ≥ 4; Android TV: Feed-Abfrage + „Im Play Store öffnen“ |
| Berechtigung `REQUEST_INSTALL_PACKAGES`, Update-FileProvider | ja | nein (`mobile|tv/src/play/AndroidManifest.xml`) |
| targetSdk / compileSdk | Handy 36, TV 35 / compileSdk 36 | gleich (Play: TV braucht nur 34) |
| Zurück-Taste | – | Predictive Back aus (`enableOnBackInvokedCallback=false`) |
| TV-Manifest | leanback `required=false` | leanback `required=true`, Banner „IPTVstream“ (`tv/src/play/res`) |
| Einstellung „Kennung gegenüber dem Anbieter“ | Option „IPTV Smarters“ vorhanden | Option ausgeblendet (alter Wert → Browser-Kennung) |
| „Deutscher Programmführer aus dem Netz“ (epgshare01) | Standard „Als Ersatz“ | Standard „Aus“ |
| Freie Sender (iptv-org) inkl. EPG (epgshare01 DE/AT/CH/IT) | Handy DE+IT, TV nur DE | gleich; EPG der Freien Sender läuft auch bei „Aus“ |
| Tests | `core/src/githubTest` | `core/src/playTest` (`FakeAppUpdateManager`) |

Die UI (`UpdateHost`, Dialoge) liegt gemeinsam in `core/src/main/…/update/`. Beide Updater haben dieselbe Klasse `UpdateChecker` mit gleicher Oberfläche.

## Bauen
```bash
# Tests und Lint der Play-Variante (Paparazzi-Screenshots nur mit -PwithScreenshots, android-35.jar)
./gradlew -Pdistribution=play testDebugUnitTest lintDebug

# signierte AABs (Upload-Schlüssel über die gleichen SIGNING_*-Variablen wie die GitHub-APK)
./gradlew -Pdistribution=play :mobile:bundleRelease :tv:bundleRelease
```
Hilfsskript (Upload-Schlüssel aus `/workspace/.iptvstream-playstore-signing`, Sentry-DSN aus `/workspace/.iptvstream-sentry-dsn`, beides nur lokal): `/workspace/playstore-iptvstream/build-play.sh`.
Store-Screenshots (Standard-Variante, Paparazzi): `./gradlew :mobile:recordPaparazziDebug :tv:recordPaparazziDebug` (Klassen `PlayStoreScreenshots`).

`versionCode` zählt gemeinsam mit der GitHub-Version. Play-Build und GitHub-Build haben unterschiedliche Signaturen, sobald Play App Signing aktiv ist.
