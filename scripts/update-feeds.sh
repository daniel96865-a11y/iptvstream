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
  local code name
  code="$(field "$apk" versionCode)"
  name="$(field "$apk" versionName)"
  test -n "$code"
  test -n "$name"
  ROOT="$ROOT" JSON="$json" CODE="$code" NAME="$name" NOTE="$note" TAG="$TAG" REPO="$REPO" APK_NAME="$(basename "$apk")" python3 - <<'PY'
import json, os
path = os.path.join(os.environ["ROOT"], os.environ["JSON"])
code = int(os.environ["CODE"])
name = os.environ["NAME"]
apk_name = os.environ["APK_NAME"]
url = f"https://github.com/{os.environ['REPO']}/releases/download/{os.environ['TAG']}/{apk_name}"
data = {}
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
if data.get("versionCode") == code and data.get("apkUrl") == url and data.get("versionName") == name:
    print(f"feed aktuell: {path}")
    raise SystemExit(0)
changelog = data.get("changelog") if data.get("versionCode") == code else os.environ["NOTE"]
out = {
    "versionCode": code,
    "versionName": name,
    "apkUrl": url,
    "changelog": changelog or f"Version {name}",
}
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(f"feed geschrieben: {path}")
PY
}

write_one "dist/iptvstream-mobile.apk" "docs/iptvstream-mobile.json" "Version ${TAG#v}"
write_one "dist/iptvstream-tv.apk" "docs/iptvstream-tv.json" "Version ${TAG#v}"
