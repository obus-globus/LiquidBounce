# Fabric late-attach transformer probe — notes + how to reproduce

## Verdict
- **Plain gate: RED.** A direct `IMixinTransformer.transformClassBytes(...)` on an ALREADY-LOADED
  target does NOT apply a config added post-boot via `Mixins.addConfiguration(...)`.
- **Recoverable: GREEN with a concrete workaround** (see below). The already-loaded target case IS
  achievable; the design's "only mix future targets" fallback is NOT required.

## Mechanism (proven from Sponge 0.8.7 bytecode + live probe)
`MixinProcessor.checkSelect` (javap):
```
if (currentEnvironment != environment) { select(environment); return; }   // full re-select
if (Mixins.getUnvisitedCount() > 0 && this.transformedCount == 0) select(environment);
```
A late-added (unvisited) config is only selected when `transformedCount == 0`. Post-boot it is huge
(596 at menu), so the config stays unvisited/unprepared and `transformClassBytes` silently skips it.
Forcing selection then hits a second, deliberate guard: `MixinInfo.readDeclaredTargets` throws
`MixinTargetAlreadyLoadedException` ("target ... was loaded too early") for a `required:true` config
whose target is already loaded.

## Workaround (verified live — X2 contains the injected handler)
1. Config `"required": false` → downgrades the "loaded too early" fatal throw to a logged ERROR;
   prepare completes and the mixin applies to the bytes you feed. (Alternative: reflectively remove the
   target from `MixinServiceKnot`'s IClassTracker so `isClassLoaded` is false; keeps `required:true`.)
2. Force selection of the pending config before the transform: reflectively set
   `MixinTransformer.processor.transformedCount = 0` (private int). checkSelect then runs
   `select()`→`selectConfigs()`→`prepareConfigs()` for the unvisited config.
Then `transformClassBytes(dot, dot, preMixinBytes)` returns bytes containing the late-added injection.
Evidence: X(no-config)=180357 → X2(workaround)=180748, `contains(marker)=true`,
`contains(probe$onGetWindow)=true`, `unvisitedAfter=0`, client stayed alive (transform is on supplied
buffers, not the live class).

## How Fabric was staged (reuse this)
Stock Fabric 26.2 (Mojmap/dev namespace, NO LiquidBounce), launched by calling KnotClient directly
with loom's captured dev recipe:
- `capture-runclient.gradle` — init script; captured loom's runClient JavaExec into `runclient-recipe.txt`
  (real main `net.fabricmc.loader.impl.launch.knot.KnotClient`, `fabric.development=true`,
  `fabric.defaultModDistributionNamespace=official`, `fabric.defaultMixinRemapType=static`).
- `stock-cp.txt` — the loom runClient classpath (250 entries) MINUS LB's 3 build dirs
  (`build/classes/{java,kotlin}/main`, `build/resources/main`) so LB is not discovered as a mod.
  Includes fabric-api + fabric-language-kotlin + sodium/lithium/iris/viafabricplus/mcef/modmenu.
- `launch-stock-fabric.ps1` — writes a java `@knot.args` (forward-slash paths), launches KnotClient on
  **GraalVM CE 25** (no jcef module), persistent via `Start-Process -PassThru`, PID -> `fabric.pid`,
  log -> `logs/stock-fabric.log`. Offline auth args (`--accessToken 0`). Reaches menu in ~6-12s.
  (The Prism "26.2 fabric" instance also reaches menu but needs a live MSA account.)

## Probe build/attach
- `src/probeagent/ProbeAgent.java` (agentmain), `src/probe/mixins/ProbeMixin.java` (@Inject
  Minecraft.getWindow HEAD), `src/probe.mixins.json`. Compiled with JBR jcef JDK against
  fabric-loader-0.19.3 + sponge-mixin-0.17.3. **No ASM bundled** (verified 0). Manifest: Agent-Class +
  Can-Retransform + Can-Redefine.
- Attach: `java -cp injector-out Injector <pid> probe-agent.jar <probe-agent.jar-as-agentArgs>`
  (JBR JDK). agentArgs = the probe jar path, used for `FabricLauncher.addToClassPath(jar,"probe")`.
- Verdict log: `%TEMP%/fabricprobe/probe-verdict.log` (+ copy in `logs/probe-verdict-GREEN-with-workaround.log`).

## Confirmed live Knot/Mixin seams (all worked)
`FabricLauncherBase.getLauncher()`, `FabricLauncher.addToClassPath(Path,String...)`,
`Mixins.addConfiguration(name)`, `MixinServiceKnot.getTransformer()` (pkg-private static, reflect),
`KnotClassLoader.getDelegate()` -> `KnotClassDelegate.getRawClassBytes/getPreMixinClassBytes`,
`IMixinTransformer.transformClassBytes` (invoke via the public interface; impl class is pkg-private).
raw=148475, preMixin=148596 (AW +121). Minecraft is `net.minecraft.client.Minecraft` (Mojmap) at runtime.
