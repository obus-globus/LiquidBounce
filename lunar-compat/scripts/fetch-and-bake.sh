#!/usr/bin/env bash
# Fetch Lunar Client's current build for a Minecraft version and produce a jar of the classes Lunar actually runs
# (vanilla remapped + Lunar's inflight patches + Lunar mixins), for the checkLunarCompat task to resolve against.
#
# No Lunar/Microsoft account is needed: this uses Lunar's public launch API (the same one the launcher uses) to get
# the artifact manifest, downloads the classpath jars + natives, then runs Genesis headless just far enough to BAKE
# the runtime classes (Genesis writes bake.zip early, then may crash at MC bootstrap in a headless/offline env — that
# is fine, the bake is already saved). The dotted-name entries in bake.zip are rewritten to a normal <internal>.class
# jar.
#
# Usage: fetch-and-bake.sh [MC_VERSION] [OUTPUT_JAR]
#   MC_VERSION  default 26.2
#   OUTPUT_JAR  default lunar-compat/lunar-classes.jar
# Requires: curl, python3, a JRE matching the MC version's Java level (26.2 -> Java 25), Xvfb (headless), unzip.
set -euo pipefail

MC_VERSION="${1:-26.2}"
OUT_JAR="${2:-lunar-compat/lunar-classes.jar}"
WORK="${LUNAR_WORK:-$(mktemp -d /tmp/lunar-bake.XXXXXX)}"
JAVA_BIN="${JAVA_BIN:-java}"
API="https://api.lunarclientprod.com/launcher/launch"
LAUNCHER_VER="$(curl -fsSL --max-time 20 https://launcherupdates.lunarclientcdn.com/latest.yml | head -1 | sed 's/version: *//;s/[^0-9.].*//' || echo 3.4.9)"
echo "[lunar] MC=$MC_VERSION launcher=$LAUNCHER_VER work=$WORK"
mkdir -p "$WORK/jars" "$WORK/natives" "$WORK/run" "$WORK/cache"

HWID="$(cat /proc/sys/kernel/random/uuid)"; IID="$(cat /proc/sys/kernel/random/uuid)"; OSREL="$(uname -r)"
curl -fsSL --max-time 60 -X POST "$API" -H 'Content-Type: application/json' -H "User-Agent: Lunar Client Launcher v$LAUNCHER_VER" \
  -d "{\"os\":\"linux\",\"os_release\":\"$OSREL\",\"arch\":\"x64\",\"hwid\":\"$HWID\",\"hwid_private\":\"$HWID\",\"installation_id\":\"$IID\",\"launcher_version\":\"$LAUNCHER_VER\",\"version\":\"$MC_VERSION\",\"branch\":\"master\",\"launch_type\":\"OFFLINE\",\"module\":\"lunar\"}" \
  -o "$WORK/resp.json"

python3 - "$WORK" <<'PY'
import json,sys
w=sys.argv[1]; d=json.load(open(f"{w}/resp.json"))
if d.get("success") is False: sys.exit(f"launch API error: {json.dumps(d)[:300]}")
ltd=d["launchTypeData"]; arts=ltd["artifacts"]
open(f"{w}/mainclass.txt","w").write(ltd["mainClass"])
open(f"{w}/cp.txt","w").write("\n".join(a["name"]+"\t"+a["url"] for a in arts if a["type"]=="CLASS_PATH"))
open(f"{w}/nat.txt","w").write("\n".join(a["name"]+"\t"+a["url"] for a in arts if a["type"]=="NATIVES"))
print(f"[lunar] mainClass={ltd['mainClass']} classpath={sum(a['type']=='CLASS_PATH' for a in arts)} natives={sum(a['type']=='NATIVES' for a in arts)}")
PY

echo "[lunar] downloading classpath jars"
while IFS=$'\t' read -r n u; do curl -fsSL --max-time 180 -o "$WORK/jars/$n" "$u"; done < "$WORK/cp.txt"
echo "[lunar] downloading + unpacking natives"
while IFS=$'\t' read -r n u; do curl -fsSL --max-time 180 -o "$WORK/natives/$n" "$u"; unzip -o -q "$WORK/natives/$n" -d "$WORK/natives/" || true; done < "$WORK/nat.txt"

# asset index (from Mojang; Genesis wants it as a program arg)
VURL="$(curl -fsSL --max-time 30 https://launchermeta.mojang.com/mc/game/version_manifest_v2.json | python3 -c "import sys,json;d=json.load(sys.stdin);print(next((v['url'] for v in d['versions'] if v['id']=='$MC_VERSION'),''))")"
AIDX="$(curl -fsSL --max-time 30 "$VURL" | python3 -c "import sys,json;print(json.load(sys.stdin)['assetIndex']['id'])")"
echo "[lunar] assetIndex=$AIDX"

CP="$(ls "$WORK"/jars/*.jar | tr '\n' ':' | sed 's/:$//')"; ICHOR_CP="$(echo "$CP" | tr ':' ',')"
MAIN="$(cat "$WORK/mainclass.txt")"
DISP="${DISPLAY:-}"
if [ -z "$DISP" ]; then Xvfb :190 -screen 0 854x480x24 -nolisten tcp >/dev/null 2>&1 & XVFB=$!; DISP=":190"; sleep 2; fi

echo "[lunar] baking via Genesis (headless; will bake then may crash — bake.zip is what we keep)"
DISPLAY="$DISP" LIBGL_ALWAYS_SOFTWARE=1 timeout "${BAKE_TIMEOUT:-180}" "$JAVA_BIN" -Xmx2G -Djava.library.path="$WORK/natives" \
  --add-modules jdk.naming.dns --add-exports jdk.naming.dns/com.sun.jndi.dns=java.naming -Dlog4j2.formatMsgNoLookups=true \
  --add-opens java.base/java.io=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -cp "$CP" "$MAIN" \
  --version "$MC_VERSION" --accessToken 0 --assetIndex "$AIDX" --userProperties {} \
  --gameDir "$WORK/run" --texturesDir "$WORK/cache/textures" --launcherVersion "$LAUNCHER_VER" \
  --hwid "$HWID" --installationId "$IID" --width 854 --height 480 \
  --workingDirectory "$WORK/run" --classpathDir "$WORK/cache" --ichorClassPath "$ICHOR_CP" > "$WORK/bake.log" 2>&1 || true
[ -n "${XVFB:-}" ] && kill "$XVFB" 2>/dev/null || true

BAKE="$(find "$WORK/cache" -name bake.zip | head -1 || true)"
[ -z "$BAKE" ] && { echo "[lunar] ERROR: no bake.zip produced. Genesis log tail:"; tail -20 "$WORK/bake.log"; exit 1; }
echo "[lunar] bake.zip: $BAKE ($(du -h "$BAKE"|cut -f1)) -> $OUT_JAR"

python3 - "$BAKE" "$OUT_JAR" <<'PY'
import sys,zipfile
src,out=sys.argv[1],sys.argv[2]
zi=zipfile.ZipFile(src); zo=zipfile.ZipFile(out,'w',zipfile.ZIP_STORED); n=0
for e in zi.namelist():
    data=zi.read(e)
    if data[:4]==b'\xca\xfe\xba\xbe':                    # class file magic; entries are dotted FQN, no extension
        zo.writestr(e.replace('.','/')+'.class', data); n+=1
zo.close(); print(f"[lunar] wrote {out}: {n} classes")
PY
echo "[lunar] done. Reference jar: $OUT_JAR"
