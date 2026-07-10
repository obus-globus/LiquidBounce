# Self-contained vanilla LiquidBounce agent

A **single fat jar** that runs LiquidBounce on **unmodified vanilla Minecraft 26.2**
with no Gradle, no hand-assembled classpath, no `-javaagent` surgery, no `-Dlb.*`
dev paths. It bundles LB's full dep tree **plus the Mixin infrastructure vanilla
lacks** (a standalone Mixin service + sponge-mixin + ASM + MixinExtras), and drives
it through the existing `VanillaLauncher` main-class + transforming-classloader
mechanism proven in `docs/vanilla-agent-spike/`.

This productionizes that throwaway spike (`run.sh`) into one buildable artifact.
The bare-vanilla side (the MC client jar + piston libraries) is the only thing the
host provides — it is the classpath the jar's main class is launched on.

## Build & run

```
./gradlew :vanillaSelfContainedAgentJar
# -> build/agent-vanilla/liquidbounce-agent-vanilla.jar  (~111 MB, 138 bundled LB jars)

java -cp <bare-vanilla-MC-classpath>:liquidbounce-agent-vanilla.jar \
     vspike.VanillaLauncher <standard MC client args>
```

`<bare-vanilla-MC-classpath>` = the vanilla MC 26.2 client jar + its piston
libraries (what a Mojang launcher downloads). `run-bare-vanilla.sh` is the exact
harness used for the evidence below.

## What's in the jar

Two layers (root = app classloader; `agent-libs/` = extracted to temp at launch):

**Root — the Mixin framework + launcher** (`Main-Class: vspike.VanillaLauncher`):
- sponge-mixin `0.17.3+mixin.0.8.7` (the only fork with `CompatibilityLevel.JAVA_25`),
  ASM `9.10.1` (unpacked **first** so it wins), MixinExtras (common), all unpacked.
- The VSpike standalone Mixin service (`IMixinService`/`IMixinServiceBootstrap`/
  `IGlobalPropertyService`) — registered via `META-INF/services` (ours only; the
  frameworks' rival service files are stripped so no LaunchWrapper/ModLauncher
  service is selected).
- **`Implementation-Version: 9.10.1` in a per-package `org/objectweb/asm/` manifest
  section** — Mixin's `JAVA_25.isSupported()` reads ASM's version from the jar
  manifest; fat-jar packaging erases it otherwise and JAVA_25 is silently rejected.

**`agent-libs/` — LB payload** (extracted to temp, loaded via a `libLoader`):
- `liquidbounce.jar` = LB's compiled classes + resources (mixin JSONs, the
  131-directive AccessWidener, assets) + the `VanillaPlatform` shim + a Platform
  SPI override (`net.ccbluex...platform.Platform` -> `VanillaPlatform`, winning over
  LB's `FabricPlatform`).
- LB's LB-owned dependency tree, filtered from the root `runtimeClasspath` by an
  **exclude-list**: drop the bare-vanilla platform (lwjgl, netty, mojang, log4j,
  guava, gson, joml, jopt-simple, oshi, jna, icu, commons, …), the Mixin infra
  (asm, mixinextras — at root), and the Fabric loader ecosystem + optional mods
  (`net.fabricmc` loader group, `maven.modrinth`). Everything else (kotlin, graalvm,
  viaversion, seedfinding, raphimc, lenni0451, mcef Java lib, fabric-api, …) is kept.
  **`lwjgl-egl` is the one lwjgl module bare-vanilla MC does not ship** (MCEF needs
  `org.lwjgl.egl.EGL14` for Linux accel detection) — kept as an explicit exception.

## How the launcher self-contains (`src/vspike/VanillaLauncher.java`)

1. Locate *this* jar, extract every `agent-libs/*.jar` to a temp dir.
2. Build `libLoader` over them (parent = the app loader that holds the Mixin
   framework). Set it as the **context classloader** *before* Mixin bootstrap, so
   Mixin's service resolves LB configs/classes/resources + the AccessWidener from
   the bundle (all reads in the VSpike service go through the context loader).
3. `MixinBootstrap.init()` + `Mixins.addConfiguration` for LB's two configs +
   `prepare()/inject()` + force `gotoPhase(INIT->DEFAULT)` (inject() does not
   advance the phase, so DEFAULT-phase configs would otherwise never prepare) +
   `MixinExtrasBootstrap.init()`.
4. Run MC through a `TransformingLoader` (URLs = MC classpath + the LB payload;
   parent = `libLoader`): `net.minecraft.*` / `net.ccbluex.*` are self-loaded,
   access-widened and Mixin-transformed (and Mixin's runtime *synthetic* classes are
   generated on demand — a bare `-javaagent` cannot create classes); everything else
   delegates to `libLoader`.

## Verified (bare-vanilla launch — `selfcontained-bare-vanilla-*`)

`java -cp <MC+piston>:liquidbounce-agent-vanilla.jar vspike.VanillaLauncher …`:

- Staged **138 bundled LB jars** out of the single fat jar; AccessWidener 131
  directives; both LB configs registered; MixinExtras bootstrapped; phase DEFAULT.
- `Launching LiquidBounce v0.38.1 by CCBlueX`, `Loaded 39 Render Pipelines`,
  configs `theme`/`modules`/`settings` all loaded & stored.
- **Zero mixin fallbacks** — all ~150 LB mixins applied (the spike's
  `VSpikeBytecodeProvider` AW-sync fix carries over; no `LVTGeneratorError`).
- **MCEF actually initialized**: `Initializing browser…` -> (EGL accel check runs,
  finds no HW accel on this VM) -> `Falling back to software rendering for browser`
  -> `Successfully initialized browser.` LB's custom MCEF menu background renders
  (see `lb-selfcontained-on-bare-vanilla.png`).
- **No missing-dep crash** — every LB dependency resolved from the bundle.

Caveat: `Failed to open OpenAL device` (no audio on the headless VM) and
software-GL are environmental, not agent/dep failures.
