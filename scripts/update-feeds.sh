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

if [ "$TAG" = "v1.0.18" ]; then
  NOTE="Übertragung ans TV funktioniert im WLAN. Die Adresse steht auf dem Fernseher. QR-Code öffnet die Seite, in der App geht ein gespeichertes Profil."
elif [ "$TAG" = "v1.0.17" ]; then
  NOTE="Anmeldung am Fernseher per QR-Code oder Code vom Handy. Die Zugangsdaten bleiben im eigenen WLAN."
else
  NOTE="Version ${TAG#v}"
fi
write_one "dist/iptvstream-mobile.apk" "docs/iptvstream-mobile.json" "$NOTE"
write_one "dist/iptvstream-tv.apk" "docs/iptvstream-tv.json" "$NOTE"
