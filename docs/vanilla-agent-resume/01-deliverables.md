# 01 — Working deliverables (build + run)

These are the artifacts that **work** and their exact build/run commands. Preserve these — they are the survivable, shippable results.

All Gradle tasks are run from the LB repo root (`.../LiquidBounce/`). MC 26.2, JDK 25.

---

## A. The three self-contained agents (on-load path)

Each bundles LB + its full dependency tree (kotlin, coroutines, MCEF, fabric-api, etc.) into one fat agent jar that, when loaded, boots a **standalone Sponge Mixin service** (`vspike.VSpikeService`) with LB's mixin configs + AccessWidener, and applies mixins **on class load** (raw Mixin output — no converter needed, because classes haven't loaded yet). The synthetic Mixin classes (`org.spongepowered.asm.synthetic.*`, `…$Anonymous$…`) are generated and defined into the system loader on demand.

### Gradle tasks (in `build.gradle.kts` root + `neoforge/build.gradle.kts`)

| Task | Produces | Agent-Class |
|---|---|---|
| `vanillaPureAgentJar` | pure `-javaagent` vanilla artifact (Premain + Agent-Class), **no launcher** | `vspike.PureVanillaAgent` |
| `vanillaSelfContainedAgentJar` | vanilla self-contained (launcher variant) | `vspike.Agent` / `vspike.VanillaLauncher` |
| `vanillaWatcherJar` | the **no-flag dynamic-attach watcher** (early-attach injector) — see §C | — |
| `fabricSelfContainedAgentJar` | Fabric self-contained agent | `vspike.Agent` |
| `neoforgeSelfContainedAgentJar` (in `neoforge/`) | NeoForge self-contained agent | `vspike.Agent` |

```bash
# build all vanilla agent jars (add -PbundleMcefNative for offline MCEF, see §B)
./gradlew vanillaPureAgentJar vanillaWatcherJar
./gradlew fabricSelfContainedAgentJar
(cd neoforge && ./gradlew neoforgeSelfContainedAgentJar)
```

Output jars land under `build/agent-vanilla/` (e.g. `liquidbounce-agent-vanilla-pure.jar`), `build/agent-fabric/`, `neoforge/build/agent-neoforge/`. (Confirm exact names with `./gradlew :taskName --console=plain` or by listing `build/agent-*`.)

### Running (pure `-javaagent`, vanilla)

```bash
java -javaagent:build/agent-vanilla/liquidbounce-agent-vanilla-pure.jar \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -cp "<vanilla MC classpath>" net.minecraft.client.main.Main \
     --version 26.2 --gameDir <dir> --assetsDir <assets> --assetIndex 32 \
     --accessToken 0 --uuid 0 --userType legacy
```

- **`--add-opens java.base/java.lang=ALL-UNNAMED`** is required for the reflective `ClassLoader.defineClass` used to inject synthetics. (The dynamic-attach `FullInjectAgent` self-opens via `redefineModule`, but the premain path needs the flag.)
- A bare-vanilla MC classpath + assets is staged at `/tmp/vspike-run/` on this VM (`/tmp/vspike-run/mc-cp.txt` = classpath, `/tmp/vspike-run/assets` = asset dir, `/tmp/vspike-run/gamedir/saves/New World` = a ready world). Recreate from the loom cache `~/.gradle/caches/fabric-loom/26.2/` if lost.

---

## B. `-PbundleMcefNative` — offline MCEF/Chromium

Gradle flag on all three agent tasks. Bundles the MCEF native (Chromium/JCEF) into the agent jar so MCEF runs **without downloading** at runtime (critical for offline/headless). `vspike.McefNative.stageIfBundled(...)` extracts + stages it on agent start.

```bash
./gradlew vanillaPureAgentJar -PbundleMcefNative
```

Verified: the LB MCEF main menu renders on all three loaders with this flag (task #20). The native lands under a staged dir (e.g. `/tmp/lb-mcef-native/` or `<gamedir>/mcef/`).

---

## C. The watcher / early-attach path — **THE ONE THAT RENDERS THE FULL UI**

This is the most important deliverable. It injects **full working LB (UI + real modules)** into a **no-flag** MC — because it attaches *early*, during boot, before the game's render/GUI classes load, so mixins apply **on-load** and LB is present when MC's startup lifecycle (crucially `ShaderManager.apply`) runs.

**Why it works and the converter path doesn't:** LB's custom render pipelines (for the CEF/MCEF texture blit, ClickGUI blur, etc.) are compiled via `ClientRenderPipelines.precompile()` which is invoked **inside `ShaderManager.apply`** (`MixinShaderManager @Inject(TAIL)`). The watcher is present when `apply` runs at startup → the pipelines compile with LB's shaders → the CEF UI composites. See `03-attach-frontier.md` §"Final root cause".

- `vanillaWatcherJar` task builds it. It's the pure-agent pipeline triggered by **early dynamic attach** instead of premain: a separate injector process polls for the MC PID and `VirtualMachine.attach(pid).loadAgent(jar)` in the early-boot window where SIGQUIT is still caught (before GLFW window init).
- Coverage caveat (documented in `docs/vanilla-dynamic-attach-spike/RESULTS.md`): pid-detection + attach handshake takes ~1.4s, so a transformer registered then catches everything loaded *after* (render/screen/client/world — the bulk of LB's targets) but misses classes loaded in the first ~1s. A premain `-javaagent` has no such gap.

### Injector

The generic injector (used for every attach in this work) is a tiny standalone:
```bash
# java -cp <injector-out> Injector <pid> <agent.jar> <agent-args>
java -cp /tmp/attach-spike/out Injector <MC_PID> <agent.jar> ""
```
It does `VirtualMachine.attach(pid).loadAgent(jar, args)`. **Uses a separate injector process** (the normal allowed model — `jdk.attach.allowAttachSelf` NOT needed). `Injector.class` is at `/tmp/attach-spike/out/`; source is trivial (attach + loadAgent + detach). Recreate if lost.

---

## D. Prerequisites recap

- MC 26.2 client jar (loom-remapped): `~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar`.
- Bare-vanilla classpath + assets: `/tmp/vspike-run/`.
- The agent jars carry LB + deps; nothing else needs to be on the MC classpath.
- JDK 25.0.3. Dynamic attach still allowed (see `04-environment-gotchas.md`).
