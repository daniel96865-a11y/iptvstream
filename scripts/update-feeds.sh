#!/bin/bash
# Schreibt docs/iptvstream-*.json für den aktuellen Tag, falls die Version noch fehlt.
# Erwartet dist/iptvstream-mobile.apk und dist/iptvstream-tv.apk sowie aapt im PATH.
set -euo pipefail

TAG="${1:?tag, z. B. v1.0.1}"
REPO="${2:?owner/name}"
AAPT="${3:-aapt}"
ROOT="${4:-.}"

field() {
  "$AAPT" dump badging "$1" | sed -n "s/.*${2}='\([^']*\)'.*/\1/p" | head -n1
}

write_one() {
  local apk="$1" json="$2" note="$3"
  local code name sha256
  code="$(field "$apk" versionCode)"
  name="$(field "$apk" versionName)"
  sha256="$(sha256sum "$apk" | awk '{print $1}')"
  test -n "$code"
  test -n "$name"
  test -n "$sha256"
  ROOT="$ROOT" JSON="$json" CODE="$code" NAME="$name" SHA256="$sha256" NOTE="$note" TAG="$TAG" REPO="$REPO" APK_NAME="$(basename "$apk")" python3 - <<'PY'
import json, os
path = os.path.join(os.environ["ROOT"], os.environ["JSON"])
code = int(os.environ["CODE"])
name = os.environ["NAME"]
sha256 = os.environ["SHA256"]
apk_name = os.environ["APK_NAME"]
url = f"https://github.com/{os.environ['REPO']}/releases/download/{os.environ['TAG']}/{apk_name}"
data = {}
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
if (data.get("versionCode") == code and data.get("apkUrl") == url and
        data.get("versionName") == name and data.get("sha256") == sha256):
    print(f"feed aktuell: {path}")
    raise SystemExit(0)
changelog = data.get("changelog") if data.get("versionCode") == code else os.environ["NOTE"]
out = {
    "versionCode": code,
    "versionName": name,
    "apkUrl": url,
    "sha256": sha256,
    "changelog": changelog or f"Version {name}",
}
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(f"feed geschrieben: {path}")
PY
}

if [ "$TAG" = "v1.0.42" ]; then
  NOTE="Senderlisten laufen flüssiger. Das Programm wird für die sichtbaren Sender vorgeladen, und die Logos halten das Scrollen nicht mehr auf."
elif [ "$TAG" = "v1.0.41" ]; then
  NOTE="Ein abgebrochener Programmführer lässt die alte Übersicht stehen. Fehlende Bereiche einer Liste werden nachgeladen. Der Programmführer aktualisiert sich auch, wenn die App offen bleibt oder zu ist."
elif [ "$TAG" = "v1.0.40" ]; then
  NOTE="Die Bereiche in den Einstellungen klappen nach unten auf. Die Optionen stehen direkt darunter, wie früher."
elif [ "$TAG" = "v1.0.39" ]; then
  NOTE="Die Schublade in den Einstellungen ist eine Glasfläche und nur so hoch wie die Optionen."
elif [ "$TAG" = "v1.0.38" ]; then
  NOTE="In den Einstellungen stehen die Bereiche untereinander. Tippen öffnet die Schublade mit den Optionen."
elif [ "$TAG" = "v1.0.37" ]; then
  NOTE="Die Einstellungen auf dem Handy sind in Bereiche geteilt. Design, Uhr, Player, Live-TV und Allgemein öffnet man über die Schublade."
elif [ "$TAG" = "v1.0.36" ]; then
  NOTE="Chromecast auf dem Handy: Im Player auf das Cast-Symbol tippen und den Fernseher wählen. An und aus in den Einstellungen, Player."
elif [ "$TAG" = "v1.0.35" ]; then
  NOTE="Das Favoriten-Widget auf dem Startbildschirm nutzt die neueste Version. Sonst keine sichtbaren Änderungen."
elif [ "$TAG" = "v1.0.34" ]; then
  NOTE="Technische Durchsicht: Der Wisch über die untere Leiste trifft den richtigen Punkt, und die Hilfebilder zeigen wieder den aktuellen Stand."
elif [ "$TAG" = "v1.0.33" ]; then
  NOTE="Die untere Leiste auf dem Handy zeigt den gewählten Punkt als blaue Kapsel mit Namen. Die anderen Punkte sind nur Symbole."
elif [ "$TAG" = "v1.0.32" ]; then
  NOTE="Der Live-Puffer bleibt bei groß wirklich länger stehen, und der kleine Balken zerlegt den Text nicht mehr."
elif [ "$TAG" = "v1.0.31" ]; then
  NOTE="Unten am Live-Sender zeigt ein Balken, wie viel vom Puffer schon geladen ist. Bei groß ist er länger, bei klein kürzer."
elif [ "$TAG" = "v1.0.30" ]; then
  NOTE="Leise Sender lassen sich im Player unter „Ton lauter“ anheben. Die Stufe gilt nur für diesen Sender und bleibt gespeichert."
elif [ "$TAG" = "v1.0.29" ]; then
  NOTE="Das Programm läuft für deutsche Sender richtiger: Sender werden nur noch Einträgen des gleichen Landes zugeordnet, und ein deutscher Programmführer aus dem Netz springt als Ersatz ein (Einstellungen, Live-TV)."
elif [ "$TAG" = "v1.0.28" ]; then
  NOTE="Große Senderlisten laden das Programm schonender. Am Fernseher kommt man in Filmen und Serien auch in die letzte Reihe und bei leerer Liste zurück zu den Kategorien."
elif [ "$TAG" = "v1.0.27" ]; then
  NOTE="Lange Kategorienamen laufen auf dem Fernseher durch, sobald die Zeile den Fokus hat. Die anderen bleiben stehen."
elif [ "$TAG" = "v1.0.26" ]; then
  NOTE="Die Kategorien links auf dem Fernseher lassen sich wieder ansteuern, ohne dass die App hängen bleibt."
elif [ "$TAG" = "v1.0.25" ]; then
  NOTE="Der Rahmen um den gewählten Bereich unten folgt der runden Ecke und schneidet sie nicht mehr."
elif [ "$TAG" = "v1.0.24" ]; then
  NOTE="Die untere Leiste liegt unter den Einstellungen und verdeckt sie nicht mehr."
elif [ "$TAG" = "v1.0.23" ]; then
  NOTE="Listen laufen flüssiger. Animationen für Fokus, Filmdetails und Spulen, abschaltbar in den Einstellungen."
elif [ "$TAG" = "v1.0.22" ]; then
  NOTE="Filme zeigen die echte Laufzeit. Die Tonspur wechselt man in den Player-Einstellungen."
elif [ "$TAG" = "v1.0.21" ]; then
  NOTE="4K-Filme und 4K-Live laufen über den Hardware-Decoder. Fehlt ein Live-Format, wird das andere probiert."
elif [ "$TAG" = "v1.0.20" ]; then
  NOTE="Der Player zeigt Auflösung, Bildformat und Tonformat aus dem laufenden Stream."
elif [ "$TAG" = "v1.0.19" ]; then
  NOTE="Bei der Adresse zum Fernseher setzt die App die Punkte. Es werden nur die Zahlen eingetippt."
elif [ "$TAG" = "v1.0.18" ]; then
  NOTE="Übertragung ans TV funktioniert im WLAN. Die Adresse steht auf dem Fernseher. QR-Code öffnet die Seite, in der App geht ein gespeichertes Profil."
elif [ "$TAG" = "v1.0.17" ]; then
  NOTE="Anmeldung am Fernseher per QR-Code oder Code vom Handy. Die Zugangsdaten bleiben im eigenen WLAN."
else
  NOTE="Version ${TAG#v}"
fi
write_one "dist/iptvstream-mobile.apk" "docs/iptvstream-mobile.json" "$NOTE"
write_one "dist/iptvstream-tv.apk" "docs/iptvstream-tv.json" "$NOTE"
