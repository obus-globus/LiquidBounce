#!/usr/bin/env bash
# Launch the self-contained vanilla agent on a BARE vanilla MC 26.2 (MC jar + piston libs only).
set -uo pipefail
WORK=/tmp/lb-vanilla-test
rm -rf "$WORK"; mkdir -p "$WORK/gamedir" "$WORK/xrt"
FAT=/tmp/lb-vanilla-agent.jar
# bare-vanilla classpath = MC client jar + piston libraries (NO LB, NO kotlin — those come from the fat jar)
MC_CP=$(paste -sd: /tmp/vspike-run/mc-cp.txt)
# only the fat jar is added on top of the bare-vanilla platform
CP="$MC_CP:$FAT"
exec env -u WAYLAND_DISPLAY XDG_RUNTIME_DIR="$WORK/xrt" DISPLAY=:99 \
  java -Xmx2g -Dmixin.env.disableRefMap=true \
  -cp "$CP" vspike.VanillaLauncher \
  --username Dev --version 26.2 --gameDir "$WORK/gamedir" \
  --assetsDir /tmp/vspike-run/assets --assetIndex 32 --accessToken 0 \
  --uuid 00000000000000000000000000000000 --userType legacy --width 1280 --height 800
