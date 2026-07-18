#!/usr/bin/env bash
# Fetch Lunar Client's current build for a Minecraft version and produce a jar of the classes Lunar actually runs
# (vanilla remapped + Lunar's inflight patches + Lunar mixins), for the checkLunarCompat task to resolve against.
#
# No Lunar/Microsoft account is needed: this uses Lunar's public launch API (the same one the launcher uses) to get
# the artifact manifest, downloads the classpath jars + natives, then runs Genesis headless just far enough to BAKE
# the runtime classes (Genesis writes bake.zip early, then may crash at MC bootstrap in a headless/offline env - that
# is fine, the bake is already saved). The dotted-name entries in bake.zip are rewritten to a normal <internal>.class
# jar.
#
# The launch API always serves the LATEST Lunar build for a given (MC version, branch) - there is no way to pin a
# historical Lunar build, which is the right default for "does it work on what users run right now". The exact build
# is recorded (Genesis logs a commit hash) into <OUTPUT_JAR>.meta so each report says what it tested. Only the public
# `master` (release) branch is reachable without a Lunar account; `beta`/`staging` return NO_PERMISSION_PRIVATE_BRANCH.
#
# Usage: fetch-and-bake.sh [MC_VERSION] [OUTPUT_JAR]
#   MC_VERSION  default: auto-detected from LiquidBounce's gradle/libs.versions.toml
#   OUTPUT_JAR  default lunar-compat/lunar-classes.jar   (+ a sibling .meta with the tested Lunar build info)
#   env LUNAR_BRANCH  default master  (beta/staging need Lunar account access)
# Requires: curl, python3, a JRE matching the MC version's Java level (26.2 -> Java 25), Xvfb (headless), unzip.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib-lunar.sh"   # shared, update-resistant helpers (MC version + launch API live here)

MC_VERSION="${1:-$(detect_mc_version || true)}"
OUT_JAR="${2:-$(lunar_repo_root)/lunar-compat/lunar-classes.jar}"
BRANCH="${LUNAR_BRANCH:-master}"
WORK="${LUNAR_WORK:-$(mktemp -d /tmp/lunar-bake.XXXXXX)}"
JAVA_BIN="${JAVA_BIN:-java}"
[ -n "$MC_VERSION" ] || { echo "[lunar] could not detect the MC version from gradle/libs.versions.toml; pass it explicitly: fetch-and-bake.sh <MC_VERSION>"; exit 2; }
LAUNCHER_VER="$(detect_launcher_version)"
HWID="$(cat /proc/sys/kernel/random/uuid)"; IID="$(cat /proc/sys/kernel/random/uuid)"   # for the Genesis bake args below
echo "[lunar] MC=$MC_VERSION branch=$BRANCH launcher=$LAUNCHER_VER work=$WORK"
mkdir -p "$WORK/jars" "$WORK/natives" "$WORK/run" "$WORK/cache"

lunar_launch_json "$MC_VERSION" "$BRANCH" "$LAUNCHER_VER" "$WORK/resp.json"

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

# asset index (from Mojang; Genesis wants it as a program arg). Fall back to the MC version if the
# version isn't in Mojang's manifest or the lookup fails, so the bake still proceeds.
VURL="$(curl -fsSL --max-time 30 https://launchermeta.mojang.com/mc/game/version_manifest_v2.json 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);print(next((v['url'] for v in d['versions'] if v['id']=='$MC_VERSION'),''))" 2>/dev/null || true)"
AIDX="$(curl -fsSL --max-time 30 "$VURL" 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin)['assetIndex']['id'])" 2>/dev/null || echo "$MC_VERSION")"
echo "[lunar] assetIndex=$AIDX"

CP=""; for jar in "$WORK"/jars/*.jar; do CP="${CP:+$CP:}$jar"; done; ICHOR_CP="$(echo "$CP" | tr ':' ',')"
MAIN="$(cat "$WORK/mainclass.txt")"
DISP="${DISPLAY:-}"
if [ -z "$DISP" ]; then
  for n in $(seq 190 320); do [ -e "/tmp/.X11-unix/X$n" ] || { DISP=":$n"; break; }; done   # first free display
  [ -n "$DISP" ] || { echo "[lunar] no free X display in :190-:320"; exit 1; }
  Xvfb "$DISP" -screen 0 854x480x24 -nolisten tcp >/dev/null 2>&1 & XVFB=$!
  for _ in $(seq 1 10); do [ -e "/tmp/.X11-unix/X${DISP#:}" ] && break; sleep 1; done
  [ -e "/tmp/.X11-unix/X${DISP#:}" ] || { echo "[lunar] Xvfb failed to start on $DISP"; exit 1; }
fi

echo "[lunar] baking via Genesis (headless; will bake then may crash - bake.zip is what we keep)"
DISPLAY="$DISP" LIBGL_ALWAYS_SOFTWARE=1 timeout "${BAKE_TIMEOUT:-180}" "$JAVA_BIN" -Xmx2G -Djava.library.path="$WORK/natives" \
  --add-modules jdk.naming.dns --add-exports jdk.naming.dns/com.sun.jndi.dns=java.naming -Dlog4j2.formatMsgNoLookups=true \
  --add-opens java.base/java.io=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -cp "$CP" "$MAIN" \
  --version "$MC_VERSION" --accessToken 0 --assetIndex "$AIDX" --userProperties '{}' \
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

# Record exactly which Lunar build was tested (Genesis logs branch + commit hash) so the report is reproducible-ish.
LUNAR_COMMIT="$(grep -aoE 'Commit Hash: [0-9a-f]+' "$WORK/bake.log" | head -1 | awk '{print $3}' || true)"
LUNAR_LOGBRANCH="$(grep -aoE 'Branch: [A-Za-z0-9._/-]+' "$WORK/bake.log" | head -1 | awk '{print $2}' || true)"
{
  echo "mc_version=$MC_VERSION"
  echo "requested_branch=$BRANCH"                        # the channel we asked the launch API for
  echo "genesis_branch=${LUNAR_LOGBRANCH:-unknown}"      # the build channel Genesis reports internally
  echo "lunar_commit=${LUNAR_COMMIT:-unknown}"
  echo "launcher_version=$LAUNCHER_VER"
  echo "fetched_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "${OUT_JAR}.meta"
echo "[lunar] tested build: MC $MC_VERSION, branch $BRANCH (genesis: ${LUNAR_LOGBRANCH:-unknown}), Lunar commit ${LUNAR_COMMIT:-unknown}"
echo "[lunar] done. Reference jar: $OUT_JAR  (build info: ${OUT_JAR}.meta)"
