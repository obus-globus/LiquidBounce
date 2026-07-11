#!/bin/bash
set -x
export DISPLAY=:121
pkill -9 -f 'gameDir /tmp/fullmc' 2>/dev/null; pkill -9 -f 'zenity.*LiquidBounce' 2>/dev/null; sleep 2; rm -f /tmp/lb-mcef-native/.lock 2>/dev/null
LOGF=/tmp/fullmc-$(date +%s).log; ln -sf "$LOGF" /tmp/fullmc.log
MC_CP=$(paste -sd: /tmp/vspike-run/mc-cp.txt)
rm -rf /tmp/fullmc; mkdir -p /tmp/fullmc/gamedir/saves /tmp/fullmc/xrt
cp -r "/tmp/vspike-run/gamedir/saves/New World" /tmp/fullmc/gamedir/saves/
printf 'onboardAccessibility:false\nnarrator:0\nrenderDistance:6\nsimulationDistance:5\npauseOnLostFocus:false\n' > /tmp/fullmc/gamedir/options.txt
# boot to MENU (no quickplay) so LB initializes calmly first
env -u WAYLAND_DISPLAY CI=1 XDG_RUNTIME_DIR=/tmp/fullmc/xrt DISPLAY=:121 java -Xmx2g -cp "$MC_CP" net.minecraft.client.main.Main \
  --username Dev --version 26.2 --gameDir /tmp/fullmc/gamedir --assetsDir /tmp/vspike-run/assets --assetIndex 32 --accessToken 0 --uuid 00000000000000000000000000000000 --userType legacy --width 1280 --height 800 > "$LOGF" 2>&1 &
MCJOB=$!
for i in $(seq 1 60); do grep -qiE 'Backend library|Narrator library' /tmp/fullmc.log && break; kill -0 $MCJOB 2>/dev/null || { echo "MC died early"; exit 1; }; sleep 3; done
sleep 12
JPID=$(for p in $(pgrep -f 'gameDir /tmp/fullmc'); do [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && echo $p && break; done)
echo "ATTACH full-agent (menu) -> $JPID"
java -cp /tmp/attach-spike/out Injector "$JPID" /tmp/full/full-agent.jar "" > /tmp/attach.log 2>&1; echo "ATTACH rc=$?"
# wait for LB to FULLY init at the menu
for i in $(seq 1 50); do grep -qiE 'successfully initialized|An error occurred|Game crashed' /tmp/fullmc.log && break; kill -0 $MCJOB 2>/dev/null || break; sleep 3; done
sleep 6
echo "=== LB init done at menu; entering world ==="
java -cp /tmp/attach-spike/out Injector "$JPID" /tmp/enterworld/enterworld.jar "New World" > /tmp/enterworld.log 2>&1; echo "ENTERWORLD rc=$?"
# wait for in-world (integrated server + spawn)
for i in $(seq 1 60); do grep -qiE 'Loaded .* advancements|Time elapsed|Changing dimension|Preparing spawn|Game crashed' /tmp/fullmc.log && break; kill -0 $MCJOB 2>/dev/null || break; sleep 3; done
sleep 25
import -window root /tmp/full/w-1-inworld.png 2>/dev/null
# enable native render modules
MODS='net.ccbluex.liquidbounce.features.module.modules.render.ModuleTracers,net.ccbluex.liquidbounce.features.module.modules.render.esp.ModuleESP,net.ccbluex.liquidbounce.features.module.modules.render.nametags.ModuleNametags,net.ccbluex.liquidbounce.features.module.modules.render.ModuleFullBright'
java -cp /tmp/attach-spike/out Injector "$JPID" /tmp/opengui/opengui.jar "$MODS" > /tmp/opengui.log 2>&1; echo "OPENGUI rc=$?"
sleep 12
WID=$(xdotool search --name 'LiquidBounce\|Minecraft' 2>/dev/null | tail -1)
xdotool mousemove --window "$WID" 640 400 2>/dev/null; sleep 3
import -window root /tmp/full/w-2-modules.png 2>/dev/null
[ -n "$WID" ] && import -window "$WID" /tmp/full/w-3-window.png 2>/dev/null
echo "DONE alive=$(kill -0 $MCJOB 2>/dev/null&&echo Y||echo N)"
