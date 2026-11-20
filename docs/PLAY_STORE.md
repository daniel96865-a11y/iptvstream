# Google-Play-Ausgabe (Flavor `play`)

Zwei Vertriebswege über die Produkt-Variante `distribution`:

| | `github` (Standard) | `play` |
|---|---|---|
| Eigene Updates (Feed, APK-Download, `REQUEST_INSTALL_PACKAGES`, FileProvider) | ja | nein, Updates nur über Play |
| „Nach Updates suchen“ in den Einstellungen | ja | nein |
| Eingebauter deutscher Programmführer aus fremder Quelle | ja | nein (nur EPG des eigenen Anbieters) |
| Name der Server-Anmeldung | „Xtream Codes“ | „Server-Login“ |
| targetSdk Handy / TV | 35 / 35 | 36 / 35 |
| TV: `android.software.leanback` | optional | Pflicht (reine TV-App) |
| WorkManager-Vordergrunddienst | ja | entfernt (wird nicht benutzt) |

Code: `core/src/github` und `core/src/play` (Update-Code), `*/src/github|play/AndroidManifest.xml`, `BuildConfig.SELF_UPDATE`, `BuildConfig.BUILTIN_GUIDE`, `BuildConfig.DISTRIBUTION`.

compileSdk bleibt 35, weil Paparazzi 1.3.5 nicht gegen API 36 rendern kann. Die App nutzt keine API-36-Schnittstellen.

## Bauen

```
# GitHub-APKs (wie bisher, CI beim Tag)
./gradlew :mobile:assembleGithubRelease :tv:assembleGithubRelease

# Play-Bundles, signiert mit dem Upload-Schlüssel (nie einchecken)
PLAY_UPLOAD_KEYSTORE=/pfad/upload.p12 PLAY_UPLOAD_ALIAS=upload PLAY_UPLOAD_PASSWORD=… \
SENTRY_DSN=… ./gradlew :mobile:bundlePlayRelease :tv:bundlePlayRelease
```

Ohne `PLAY_UPLOAD_*` werden Play-Release-Bundles mit dem Debug- oder Release-Schlüssel signiert und sind nicht für Play gedacht.

## Tests

```
./gradlew testGithubDebugUnitTest testPlayDebugUnitTest lintGithubDebug lintPlayDebug
./gradlew :mobile:recordPaparazziPlayDebug --tests '*StoreScreenshots*'   # Store-Screenshots Handy
./gradlew :tv:recordPaparazziPlayDebug --tests '*TvStoreScreenshots*'     # Store-Screenshots TV
```
