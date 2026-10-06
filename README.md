# IPTVstream

IPTV-Player für Android-Smartphones und Android TV / Fire TV.

**DG Studios** · [infos@dg-studios.de](mailto:infos@dg-studios.de)

Die App enthält keine Senderlisten. Sie spielt nur Live-TV, Filme und Serien von einem IPTV-Zugang, den du selbst einträgst (Xtream Codes oder M3U/M3U8).

## Download

| Gerät | APK |
|---|---|
| Smartphone / Tablet | [iptvstream-mobile.apk](https://github.com/daniel96865-a11y/iptvstream/releases/latest/download/iptvstream-mobile.apk) |
| Android TV / Fire TV | [iptvstream-tv.apk](https://github.com/daniel96865-a11y/iptvstream/releases/latest/download/iptvstream-tv.apk) |

Aktuelle Version: [Release v1.0.6](https://github.com/daniel96865-a11y/iptvstream/releases/tag/v1.0.6)

Installation außerhalb des Play Store: in den Android-Einstellungen „Unbekannte Apps“ für den Browser bzw. Dateimanager erlauben, die APK öffnen und installieren. Auf Fire TV die APK per „Downloader“ oder `adb install` aufspielen.

## Was die App kann

Zwei Oberflächen in einem Projekt:

| Modul | Zielgerät | Oberfläche |
|---|---|---|
| `:mobile` | Smartphone/Tablet | Glas-Look, untere Navigation (blendet beim Scrollen aus), Player in der aktuellen Ausrichtung |
| `:tv` | Android TV / Fire TV | Komplett per Fernbedienung, deutlicher Fokus, Zahleneingabe für Sender |
| `:core` | beide | Daten, Repository, EPG, Player, Einstellungen, ViewModels |

- Anmeldung per **Xtream Codes** (Server, Benutzer, Passwort) oder **M3U/M3U8**. Mehrere Profile, bearbeiten und löschen. Nach dem Login werden die Inhalte automatisch geladen.
- Live-TV, Filme, Serien, Kategorien, Favoriten, Suche, zuletzt gesehen.
- **EPG** (Xtream/XMLTV): lokal zwischengespeichert, sofort sichtbar, Aktualisierung im Hintergrund, manuell mit Fortschritt, Zuordnung auch bei HD/FHD/4K-Namen.
- Live-TV: Sendernummer, Logo, Jetzt/Danach mit Fortschritt, letzter Sender, gemerkte Listenposition (auch nach Neustart).
- Filme und Serien: Details, Fortsetzen, Fortschritt, nächste Episode.
- Player (Media3/ExoPlayer): HLS, TS, MP4, MKV, Audio- und Untertitelspuren, automatische Audiospur (Deutsch, Polnisch oder automatisch) mit Fallback, Decoder-Fallback, Passthrough (standardmäßig aus), Stereo-Fallback, Bildmodi, automatisches Wiederverbinden. AC3, E-AC3, MP2 und DTS werden bei Bedarf per FFmpeg dekodiert; Hardware-Decoder bleiben zuerst dran. Die Puffergrößen sind unverändert.
- Handy-Player: startet in der aktuellen Ausrichtung. Querformat nur, wenn das Gerät gedreht wird und die Autorotation an ist. Ist die Autorotation aus, bleibt Hochformat, ein Knopf schaltet auf Vollbild.
- Updates in der App: beim Start, höchstens alle vier Stunden, und in den Einstellungen über „Nach Updates suchen“. Neuere Versionen zeigen den Changelog; die APK wird geladen und mit dem System-Installer installiert.
- Einstellungen: Design (Akzent, Hintergrund, Glass-Stärke, Animationen, Dunkel/Hell), Uhr, Player, Live-TV, Allgemein inklusive Version. Alles bleibt gespeichert.

## Updates

Öffentliche Feeds auf `main`:

- https://raw.githubusercontent.com/daniel96865-a11y/iptvstream/main/docs/iptvstream-mobile.json
- https://raw.githubusercontent.com/daniel96865-a11y/iptvstream/main/docs/iptvstream-tv.json

Felder: `versionCode`, `versionName`, `apkUrl`, `changelog`. `apkUrl` zeigt auf die Release-Dateien `iptvstream-mobile.apk` und `iptvstream-tv.apk`. Bei einem Tag `v*` aktualisiert GitHub Actions diese Dateien auf `main`, falls die gebaute Version noch nicht eingetragen ist (`scripts/update-feeds.sh`).

Der Glas-Look auf dem Handy sind halbtransparente, getönte Flächen mit Rand und Verlauf.

## TV-Bedienung

- **Oben:** Tab-Leiste. Fokus wählt den Tab, ↓ springt in den Inhalt, Zurück springt zur Tab-Leiste.
- **Live-Liste:** OK spielt ab. Lange OK oder die Menü-Taste setzt einen Favoriten oder den Startsender. Zifferntasten geben die Sendernummer ein, OK bestätigt sofort.
- **Player Live:** ▲/▼ oder Kanal-Tasten wechseln den Sender. ► oder Menü öffnet die Einstellungen (Audio, Untertitel, Bildmodus, Favorit). OK zeigt die Info. Zifferntasten springen direkt zum Sender.
- **Player Film/Serie:** ◄/► spult ±10 Sekunden (gehalten schneller). OK pausiert. ▼ oder Menü öffnet die Einstellungen. Zurück beendet die Wiedergabe.

## Bauen

Voraussetzungen: JDK 17, Android SDK (Plattform 35, Build-Tools 35). Mindestversion der App: Android 7.0 (API 24), damit die Release-Signatur nur APK Signature Scheme v2 und v3 braucht.

```bash
./gradlew :mobile:assembleRelease :tv:assembleRelease
```

Ohne Signatur-Variablen werden die Release-APKs mit dem Debug-Schlüssel signiert, damit sie lokal installierbar bleiben:

```text
mobile/build/outputs/apk/release/mobile-release.apk
tv/build/outputs/apk/release/tv-release.apk
```

Für eine Release-Signatur (v2 und v3) diese Umgebungsvariablen oder Gradle-Properties setzen:

| Variable | Bedeutung |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | PKCS12-Keystore, Base64-kodiert. Der Build dekodiert es in eine temporäre Datei und signiert damit. |
| `SIGNING_STORE_PASSWORD` | Keystore-Passwort |
| `SIGNING_KEY_PASSWORD` | Schlüssel-Passwort |
| `SIGNING_KEY_ALIAS` | Schlüsselalias |

Beispiel:

```bash
export SIGNING_KEYSTORE_BASE64="$(base64 -w0 release.keystore)"
export SIGNING_STORE_PASSWORD="…"
export SIGNING_KEY_PASSWORD="…"
export SIGNING_KEY_ALIAS="iptvstream"
./gradlew :mobile:assembleRelease :tv:assembleRelease
```

Keystore-Dateien (`*.jks`, `*.keystore`) gehören nicht ins Repository.

GitHub Actions (`.github/workflows/build.yml`) baut beide APKs bei jedem Push, lädt sie als Artefakt hoch und hängt sie bei Tags `v*` an ein GitHub-Release (`iptvstream-mobile.apk`, `iptvstream-tv.apk`). Danach werden die Update-Feeds auf `main` nachgezogen. In CI werden die vier Variablen aus den Repository-Secrets gelesen; das Keystore wird vor dem Build dekodiert.

## Pakete

| Modul | applicationId / Namespace |
|---|---|
| `:mobile` | `de.dgstudios.iptvstream` |
| `:tv` | `de.dgstudios.iptvstream.tv` |
| `:core` | `de.dgstudios.iptvstream.core` |
