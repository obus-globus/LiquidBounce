#!/usr/bin/env bash
# Run full LiquidBounce on a Fabric install via a Java AGENT — LB is NOT installed as a mod.
# Reproducible: builds the agent from committed sources, then launches loom's runClient with
# an init script that (1) removes LB's own build output from the mod classpath so Fabric does
# NOT discover it as a mod, and (2) attaches the agent, which injects LB into the live Knot
# (addToClassPath + AccessWidener via ClassTweaker + Mixins.addConfiguration).
#
# Prereq: LiquidBounce's Fabric dev env resolves (loom caches populated by a prior build).
# Usage:  docs/fabric-agent-spike/fabric-agent-run.sh        (Xvfb :99; override LBF_DISPLAY)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${LBF_WORK:-/tmp/lb-fabric-agent}"
GC="$HOME/.gradle/caches/modules-2/files-2.1"
DISPLAY_ID="${LBF_DISPLAY:-:99}"
say(){ echo "[fabric-agent] $*"; }
firstjar(){ find "$GC/$1" -name "$2" 2>/dev/null | grep -v sources | head -1; }

rm -rf "$WORK"; mkdir -p "$WORK/out"

# --- 0. deps for compiling the agent (from the Gradle cache) --------------------
SPONGE=$(firstjar net.fabricmc/sponge-mixin 'sponge-mixin-0.17.3*.jar')
ASM=$(firstjar org.ow2.asm/asm/9.9.1 'asm-9.9.1.jar')
ASMTREE=$(firstjar org.ow2.asm/asm-tree/9.9.1 'asm-tree-9.9.1.jar')
FL=$(firstjar net.fabricmc/fabric-loader 'fabric-loader-*.jar')
[ -n "$SPONGE" ] && [ -n "$FL" ] || { echo "MISSING sponge-mixin/fabric-loader in cache — build LB once first"; exit 1; }

# --- 1. compile the committed agent + build the agent jar (no bundled ASM!) ------
# NB: array, not $(...) — the repo path contains a space that would word-split.
mapfile -t AGENT_SRC < <(find "$HERE/lb-agent/src/lbagent" -name '*.java')
javac --release 25 -cp "$SPONGE:$ASM:$ASMTREE:$FL" -d "$WORK/out" "${AGENT_SRC[@]}"
printf 'Manifest-Version: 1.0\nPremain-Class: lbagent.LBAgent\nCan-Retransform-Classes: true\n' > "$WORK/MANIFEST.MF"
( cd "$WORK/out" && jar cfm "$WORK/lbagent.jar" "$WORK/MANIFEST.MF" . )
say "built lbagent.jar (bundled ASM: $(jar tf "$WORK/lbagent.jar" | grep -c objectweb) — must be 0, Fabric aborts on duplicate ASM)"

# --- 2. ensure LB is compiled (agent injects these build outputs) ---------------
( cd "$REPO" && ./gradlew classes processResources -q )
say "LB build outputs ready"

# --- 3. init script: drop LB from the mod classpath + attach the agent ----------
cat > "$WORK/fabric-agent.gradle" <<EOF
gradle.projectsEvaluated {
  rootProject.tasks.matching { it.name == "runClient" }.configureEach { t ->
    if (t instanceof JavaExec) {
      def je = t as JavaExec
      def root = "$REPO"
      def lbDirs = [root + "/build/classes/java/main", root + "/build/classes/kotlin/main", root + "/build/resources/main"]
      je.classpath = je.classpath.filter { f -> !(f.absolutePath in lbDirs) }   // LB is NOT a mod
      je.jvmArgs("-javaagent:$WORK/lbagent.jar",
                 "-Dlb.classesKotlin=" + root + "/build/classes/kotlin/main",
                 "-Dlb.classesJava="   + root + "/build/classes/java/main",
                 "-Dlb.resources="     + root + "/build/resources/main")
      println "[LB-AGENT-INIT] LB removed from mod classpath; agent attached to runClient"
    }
  }
}
EOF
say "launching bare Fabric + LB agent (LB loaded via agent, not as a mod)…"

# --- 4. launch -----------------------------------------------------------------
cd "$REPO"
exec env -u WAYLAND_DISPLAY DISPLAY="$DISPLAY_ID" \
  ./gradlew :runClient --init-script "$WORK/fabric-agent.gradle" --console=plain
