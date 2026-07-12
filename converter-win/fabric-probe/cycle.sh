#!/bin/bash
# cycle.sh — recompile changed converter sources, patch the agent jar, relaunch stock Fabric, attach, dump log.
set -e
ROOT=/c/Dev/ProjectsSorted/LiquidBounceSpecial/LiquidBounce
CW=$ROOT/converter-win
FP=$CW/fabric-probe
JDK=/c/Users/Raphael/.jdks/jbrsdk_jcef-25.0.3/bin
PA=$(cygpath -w "$ROOT/build/agent-vanilla/liquidbounce-agent-vanilla-pure.jar")
OUT=$(cygpath -w "$ROOT/build/full-agent/out")
FULLJAR="$ROOT/build/full-agent/full-agent.jar"

# 1. kill any running MC
if [ -f "$FP/fabric.pid" ]; then PID=$(cat "$FP/fabric.pid" | tr -d '\r'); powershell.exe -Command "Stop-Process -Id $PID -Force -ErrorAction SilentlyContinue" >/dev/null 2>&1 || true; sleep 1; fi

# 2. recompile the converter sources passed as args (default: FabricPlatform only)
FILES="${*:-FabricPlatform.java}"
SRCPATHS=""
for f in $FILES; do SRCPATHS="$SRCPATHS $CW/src/$f"; done
"$JDK/javac.exe" --release 25 -cp "$PA;$OUT" -d "$OUT" $SRCPATHS
echo "[cycle] compiled: $FILES"

# 3. patch changed classes into the jar (all .class newer than the jar)
cd "$OUT"
CHANGED=$(find . -name '*.class' -newer "$FULLJAR" | sed 's#^\./##')
if [ -n "$CHANGED" ]; then "$JDK/jar.exe" uf "$(cygpath -w "$FULLJAR")" $CHANGED; echo "[cycle] patched: $CHANGED"; fi

# 4. relaunch
cd "$FP"
rm -f logs/stock-fabric.log logs/stock-fabric.log.err logs/lb-inject.log
powershell.exe -ExecutionPolicy Bypass -File ./launch-stock-fabric.ps1 >/dev/null 2>&1
sleep 2; PID=$(cat fabric.pid | tr -d '\r'); echo "[cycle] launched PID=$PID"
for i in $(seq 1 40); do sleep 2; if grep -qiE "Sound engine started" logs/stock-fabric.log 2>/dev/null; then echo "[cycle] menu ready (iter $i)"; break; fi; done

# 5. attach
cd "$CW"
AGENT=$(cygpath -w "$FULLJAR")
LOGF="C:/Dev/ProjectsSorted/LiquidBounceSpecial/LiquidBounce/converter-win/fabric-probe/logs/lb-inject.log"
"$JDK/java.exe" -cp out Injector "$PID" "$AGENT" "logFile=$LOGF" 2>&1 | grep -iE "Agent loaded|FAILED|Exception" | head -3 || true
sleep 8
echo "===== lb-inject.log ====="
cat "$FP/logs/lb-inject.log"
