#!/usr/bin/env bash
# Run full LiquidBounce on a NeoForge install via a Java AGENT — LB is NOT installed as a mod.
# Reproducible from committed sources. The agent (docs/neoforge-agent-probe/lb-agent) hooks FML 11:
#   - installs a child-first fallback loader on the TransformingClassLoader (LB classes + resources,
#     analogue of Fabric addToClassPath(Knot));
#   - loads LB's AccessTransformer into FML's live AT engine;
#   - registers LB's real mixin configs into the live FMLMixinService.
# LB then bootstraps like on Fabric (MixinMinecraft fires ClientStartEvent).
#
# Usage: docs/neoforge-agent-probe/neoforge-agent-run.sh     (Xvfb :99; override LBNF_DISPLAY)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${LBNF_WORK:-/tmp/lb-neoforge-agent}"
GC="$HOME/.gradle/caches/modules-2/files-2.1"
DISPLAY_ID="${LBNF_DISPLAY:-:99}"
say(){ echo "[neoforge-agent] $*"; }
firstjar(){ find "$GC/$1" -name "$2" 2>/dev/null | grep -v sources | head -1; }

rm -rf "$WORK"; mkdir -p "$WORK/out"

# --- 0. deps to compile the agent (JDK-only classes + reflection; no external ASM) --------------
#     nfagent uses only java.* at compile time — no classpath needed.
# --- 1. compile committed agent sources + jar (0 bundled ASM) -----------------------------------
mapfile -t SRC < <(find "$HERE/lb-agent/src" -name '*.java')
javac --release 25 -d "$WORK/out" "${SRC[@]}"
printf 'Manifest-Version: 1.0\nPremain-Class: nfagent.NFAgent\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n' > "$WORK/MANIFEST.MF"
( cd "$WORK/out" && jar cfm "$WORK/nfagent.jar" "$WORK/MANIFEST.MF" nfagent )
say "built nfagent.jar (bundled ASM: $(jar tf "$WORK/nfagent.jar" | grep -c objectweb))"

# --- 2. ensure LB neoforge is built (classes + resources incl. the converted AccessTransformer) --
( cd "$REPO" && ./gradlew :neoforge:classes :neoforge:processResources -q )
say "LB neoforge build outputs ready"

# --- 3. init script: deregister LB as a mod + attach agent + point it at LB's dirs/libs/AT --------
cat > "$WORK/nf-agent.gradle" <<EOF
gradle.projectsEvaluated {
  rootProject.subprojects.each { sp ->
    if (sp.name != 'neoforge') return
    sp.tasks.matching { it.name == "runClient" }.configureEach { t ->
      if (t instanceof JavaExec) {
        def je = t as JavaExec
        je.jvmArgumentProviders.clear()   // ModFoldersProvider -> LB deregistered as a mod
        def root = sp.projectDir.absolutePath
        def dirs = ["\$root/build/classes/java/main","\$root/build/classes/kotlin/main","\$root/build/resources/main"].join(File.pathSeparator)
        // LB's runtime deps for the fallback loader. Platform classes (asm, guava, MC, NeoForge…)
        // resolve parent-first via the loader's modules; LB-specific + kotlin/kotlinx/atomicfu jars
        // are defined by the fallback. Exclude log4j — its provider discovery breaks on duplicate
        // getResources entries from a second copy in the fallback.
        def libs = ""
        try { libs = sp.configurations.runtimeClasspath.files.findAll{ it.name.endsWith('.jar') && !(it.name ==~ /(?i).*log4j.*/) }.collect{ it.absolutePath }.join(File.pathSeparator) } catch (e) { println "[NFAGENT-INIT] libs resolve failed: \$e" }
        je.jvmArgs("-Dfml.earlyWindowControl=false",  // fatal errors -> console instead of the (software-GL) error screen
                   "-javaagent:$WORK/nfagent.jar",
                   "-Dlb.buildDirs=" + dirs,
                   "-Dlb.libs=" + libs,
                   "-Dlb.at=\$root/build/resources/main/META-INF/accesstransformer.cfg")
        println "[NFAGENT-INIT] LB deregistered as mod; agent attached; " + (libs.isEmpty()?0:libs.split(File.pathSeparator as String).length) + " lib jars"
      }
    }
  }
}
EOF
say "launching NeoForge + LB agent (LB loaded via agent, not a mod)…"

# --- 4. launch -----------------------------------------------------------------------------------
cd "$REPO"
exec env -u WAYLAND_DISPLAY DISPLAY="$DISPLAY_ID" \
  ./gradlew :neoforge:runClient --init-script "$WORK/nf-agent.gradle" --console=plain
