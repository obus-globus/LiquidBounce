#!/usr/bin/env bash
# Launch full LiquidBounce on UNMODIFIED vanilla Minecraft 26.2 via a Java agent
# (transforming classloader). Self-contained: rebuilds the agent, VanillaPlatform,
# corrected resources and the whole classpath from committed sources + Gradle output.
#
# Prerequisites (all produced by building LiquidBounce normally, once):
#   - ./gradlew classes processResources        (LB dev classes + resources)
#   - the MC 26.2 client jar + libs in the Gradle caches (populated by a prior build)
#   - JDK 21+ (tested on 25), and an X display for rendering (Xvfb :99 by default)
#
# Usage:  docs/vanilla-agent-spike/run.sh            # launches on DISPLAY (default :99)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${VSPIKE_WORK:-/tmp/vspike-run}"
GC="$HOME/.gradle/caches"
MODS="$GC/modules-2/files-2.1"
MCVER="26.2"; ASSET_INDEX="32"
MC_JAR="$GC/neoformruntime/artifacts/minecraft_${MCVER}_client.jar"
MC_MANIFEST="$GC/neoformruntime/artifacts/minecraft_${MCVER}_version_manifest.json"
LOOM_ASSETS="$GC/fabric-loom/assets"
DISPLAY_ID="${VSPIKE_DISPLAY:-:99}"   # default to Xvfb :99; override with VSPIKE_DISPLAY

say(){ echo "[run] $*"; }
need(){ [ -e "$1" ] || { echo "MISSING: $1  ($2)"; exit 1; }; }

rm -rf "$WORK"; mkdir -p "$WORK"/{out,stage,lbclasses/META-INF/services,assets/indexes}

# --- 0. prerequisites -------------------------------------------------------
need "$MC_JAR" "MC client jar - build the project once to populate caches"
need "$REPO/build/classes/kotlin/main" "LB kotlin classes - run: ./gradlew classes"
need "$REPO/build/resources/main/liquidbounce.mixins.json" "LB resources - run: ./gradlew processResources"
firstjar(){ find "$MODS/$1" -name "$2" 2>/dev/null | grep -v sources | head -1; }
SPONGE=$(firstjar "net.fabricmc/sponge-mixin/0.17.3+mixin.0.8.7" "sponge-mixin-*.jar")
MEXTRAS=$(firstjar "io.github.llamalad7/mixinextras-common/0.5.4" "mixinextras-common-*.jar")
declare -a ASM=(); for m in asm asm-tree asm-commons asm-analysis asm-util; do
  ASM+=("$(firstjar "org.ow2.asm/$m/9.9.1" "$m-9.9.1.jar")"); done
need "$SPONGE" "sponge-mixin 0.17.3"; need "$MEXTRAS" "mixinextras-common 0.5.4"; need "${ASM[0]}" "asm 9.9.1"
ASM_VER="9.9.1"
say "deps: sponge-mixin + asm $ASM_VER + mixinextras resolved from Gradle cache"

# --- 1. compile committed agent sources ------------------------------------
CCP="$SPONGE:$(IFS=:; echo "${ASM[*]}"):$MEXTRAS"
mapfile -t SRCS < <(find "$HERE/src/vspike" -name '*.java')   # array: safe with spaces in paths
javac --release 25 -cp "$CCP" -d "$WORK/out" "${SRCS[@]}"
say "compiled agent sources ($(find "$WORK/out" -name '*.class' | wc -l) classes)"

# --- 2. assemble fat agent jar (with ASM impl-version in the manifest!) -----
( cd "$WORK/stage"
  jar xf "$SPONGE"; for j in "${ASM[@]}"; do jar xf "$j"; done; jar xf "$MEXTRAS" )
cp -r "$WORK/out/"* "$WORK/stage/"
cp "$HERE/src/vspike.mixins.json" "$WORK/stage/"
find "$WORK/stage" -name 'module-info.class' -delete
rm -f "$WORK/stage/META-INF"/*.SF "$WORK/stage/META-INF"/*.RSA "$WORK/stage/META-INF"/*.DSA "$WORK/stage/META-INF/MANIFEST.MF"
echo 'vspike.VSpikeService'          > "$WORK/stage/META-INF/services/org.spongepowered.asm.service.IMixinService"
echo 'vspike.VSpikeServiceBootstrap' > "$WORK/stage/META-INF/services/org.spongepowered.asm.service.IMixinServiceBootstrap"
echo 'vspike.VSpikeGlobalProps'      > "$WORK/stage/META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService"
cat > "$WORK/manifest.mf" <<MF
Manifest-Version: 1.0
Premain-Class: vspike.Agent
Can-Retransform-Classes: true
Can-Redefine-Classes: true

Name: org/objectweb/asm/
Implementation-Title: ASM
Implementation-Version: $ASM_VER
MF
jar cfm "$WORK/agent.jar" "$WORK/manifest.mf" -C "$WORK/stage" .
say "built agent.jar (ASM Implementation-Version pinned so Mixin accepts JAVA_25)"

# --- 3. VanillaPlatform + corrected resources (single Platform service) -----
KOTLIN=$(firstjar "org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-2*.jar")
javac --release 25 -cp "$REPO/build/classes/kotlin/main:$REPO/build/classes/java/main:$MC_JAR:$KOTLIN" \
  -d "$WORK/lbclasses" "$HERE/src/VanillaPlatform.java"
cp -r "$REPO/build/resources/main" "$WORK/lbres"
echo 'net.ccbluex.liquidbounce.platform.vanilla.VanillaPlatform' \
  > "$WORK/lbres/META-INF/services/net.ccbluex.liquidbounce.platform.Platform"
say "compiled VanillaPlatform; overrode Platform service -> VanillaPlatform"

# --- 4. LB runtime deps via Gradle (minus competing Mixin-service jars) -----
( cd "$REPO" && ./gradlew -q --offline --init-script "$HERE/print-runtime-cp.gradle" \
   -Dvspike.cpOut="$WORK/lb-runtime-cp.txt" :printRtCp ) || \
( cd "$REPO" && ./gradlew -q --init-script "$HERE/print-runtime-cp.gradle" \
   -Dvspike.cpOut="$WORK/lb-runtime-cp.txt" :printRtCp )
# drop fabric-loader + the dep copy of sponge-mixin (they ship rival Mixin services)
grep -viE 'fabric-loader|/sponge-mixin/' "$WORK/lb-runtime-cp.txt" > "$WORK/lb-deps.txt"
say "LB runtime deps: $(wc -l < "$WORK/lb-deps.txt") (fabric-loader + dep sponge-mixin excluded)"

# --- 5. vanilla MC classpath (Mojmap client jar + linux-x64 libraries) ------
python3 - "$MC_MANIFEST" "$MODS" "$MC_JAR" "$WORK/mc-cp.txt" <<'PY'
import json,sys,os,glob,platform
man,mods,mcjar,out=sys.argv[1:5]
libs=json.load(open(man)).get("libraries",[])
paths=[l.get("downloads",{}).get("artifact",{}).get("path") for l in libs]
def keep(p):
    if not p: return False
    b=os.path.basename(p)
    if '-natives-' in b: return 'natives-linux.jar' in b and 'arm' not in b and 'x86' not in b
    return True
res=[]
for p in filter(keep,paths):
    hits=glob.glob(f"{mods}/**/{os.path.basename(p)}",recursive=True)
    if hits: res.append(hits[0])
res.append(mcjar)
open(out,"w").write("\n".join(res)+"\n")   # trailing newline: cat-concat must not fuse entries
print(f"[run] MC classpath: {len(res)} entries (platform {platform.machine()})")
PY

# --- 6. assets (loom stores index as <ver>-<n>.json; MC wants <n>.json) -----
need "$LOOM_ASSETS/indexes/${MCVER}-${ASSET_INDEX}.json" "loom assets index"
ln -sf "$LOOM_ASSETS/indexes/${MCVER}-${ASSET_INDEX}.json" "$WORK/assets/indexes/${ASSET_INDEX}.json"
ln -sf "$LOOM_ASSETS/objects" "$WORK/assets/objects"

# --- 7. full launch classpath (Mojmap MC first so it wins) ------------------
{ cat "$WORK/mc-cp.txt"
  echo "$REPO/build/classes/kotlin/main"; echo "$REPO/build/classes/java/main"
  echo "$WORK/lbres"; echo "$WORK/lbclasses"
  cat "$WORK/lb-deps.txt"; echo "$WORK/agent.jar"
} | awk 'NF' | paste -sd: > "$WORK/launch-cp.txt"
mkdir -p "$WORK/gamedir" "$WORK/xrt"
say "launching LiquidBounce on vanilla MC $MCVER (DISPLAY=$DISPLAY_ID) ..."

# --- 8. launch --------------------------------------------------------------
exec env -u WAYLAND_DISPLAY XDG_RUNTIME_DIR="$WORK/xrt" DISPLAY="$DISPLAY_ID" \
  java -Xmx2g -Dmixin.env.disableRefMap=true \
  -Dvspike.configs=liquidbounce.mixins.json,liquidbounce-fabric.mixins.json \
  -Dvspike.gameDir="$WORK/gamedir" \
  -cp "$(cat "$WORK/launch-cp.txt")" \
  vspike.VanillaLauncher \
  --username Dev --version "$MCVER" --gameDir "$WORK/gamedir" \
  --assetsDir "$WORK/assets" --assetIndex "$ASSET_INDEX" --accessToken 0 \
  --uuid 00000000000000000000000000000000 --userType legacy --width 1280 --height 800
