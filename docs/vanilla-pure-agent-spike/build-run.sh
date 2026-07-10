#!/usr/bin/env bash
set -uo pipefail
HERE=/tmp/pure-spike
GC="$HOME/.gradle/caches"; MODS="$GC/modules-2/files-2.1"
MC_JAR="$GC/fabric-loom/26.2/minecraft-client.jar"
firstjar(){ find "$MODS/$1" -name "$2" 2>/dev/null | grep -v sources | head -1; }
SPONGE=$(firstjar "net.fabricmc/sponge-mixin/0.17.3+mixin.0.8.7" "sponge-mixin-*.jar")
MEXTRAS=$(firstjar "io.github.llamalad7/mixinextras-common/0.5.4" "mixinextras-common-*.jar")
declare -a ASM=(); for m in asm asm-tree asm-commons asm-analysis asm-util; do ASM+=("$(firstjar "org.ow2.asm/$m" "$m-*.jar")"); done
ASM_VER=$(basename "${ASM[0]}" | sed -E 's/asm-([0-9.]+)\.jar/\1/')
CCP="$SPONGE:$(IFS=:; echo "${ASM[*]}"):$MEXTRAS:$MC_JAR"
echo "[build] asm=$ASM_VER  sponge=$(basename "$SPONGE")"
rm -rf "$HERE/out" "$HERE/stage" "$HERE/agent.jar"; mkdir -p "$HERE/out" "$HERE/stage/META-INF/services"
mapfile -t SRCS < <(find "$HERE/src" -name '*.java')
javac --release 25 -cp "$CCP" -d "$HERE/out" "${SRCS[@]}" 2>&1 | head -20 || { echo "COMPILE FAILED"; exit 1; }
echo "[build] compiled $(find "$HERE/out" -name '*.class' | wc -l) classes"
( cd "$HERE/stage"; jar xf "$SPONGE"; for j in "${ASM[@]}"; do jar xf "$j"; done; jar xf "$MEXTRAS" )
cp -r "$HERE/out/"* "$HERE/stage/"
cp "$HERE/src/pure.mixins.json" "$HERE/stage/"
find "$HERE/stage" -name 'module-info.class' -delete
rm -f "$HERE/stage/META-INF"/*.SF "$HERE/stage/META-INF"/*.RSA "$HERE/stage/META-INF"/*.DSA "$HERE/stage/META-INF/MANIFEST.MF"
echo 'vspike.VSpikeService'          > "$HERE/stage/META-INF/services/org.spongepowered.asm.service.IMixinService"
echo 'vspike.VSpikeServiceBootstrap' > "$HERE/stage/META-INF/services/org.spongepowered.asm.service.IMixinServiceBootstrap"
echo 'vspike.VSpikeGlobalProps'      > "$HERE/stage/META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService"
cat > "$HERE/manifest.mf" <<MF
Manifest-Version: 1.0
Premain-Class: vspike.PureAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: true

Name: org/objectweb/asm/
Implementation-Title: ASM
Implementation-Version: $ASM_VER
MF
jar cfm "$HERE/agent.jar" "$HERE/manifest.mf" -C "$HERE/stage" .
echo "[build] agent.jar $(du -h "$HERE/agent.jar"|cut -f1)"

# bare-vanilla classpath (MC jar + piston libs) — NO custom launcher, pure -javaagent on stock Main
MC_CP=$(paste -sd: /tmp/vspike-run/mc-cp.txt)
rm -rf "$HERE/run"; mkdir -p "$HERE/run/gamedir" "$HERE/run/xrt"
echo "[run] launching stock net.minecraft.client.main.Main under -javaagent (no launcher main class)"
exec env -u WAYLAND_DISPLAY XDG_RUNTIME_DIR="$HERE/run/xrt" DISPLAY=:99 timeout 90 java -Xmx2g \
  -javaagent:"$HERE/agent.jar" \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -Dmixin.env.disableRefMap=true \
  -cp "$MC_CP" net.minecraft.client.main.Main \
  --username Dev --version 26.2 --gameDir "$HERE/run/gamedir" \
  --assetsDir /tmp/vspike-run/assets --assetIndex 32 --accessToken 0 \
  --uuid 00000000000000000000000000000000 --userType legacy --width 1280 --height 800
