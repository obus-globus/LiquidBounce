# LiquidBounce late-attach injector

Inject **full LiquidBounce** into an **already-running, unmodified** Minecraft client — vanilla, Fabric, or
NeoForge — by dynamically attaching a Java agent to the live JVM. No mod install, no launcher, no JVM flags on
the target, and **no on-load/`-javaagent` path**: this is attach-into-a-fully-started-game only.

## How it works

At startup, mixins normally apply *as classes load*. When you attach to an already-running client the target
classes are already defined, and `Instrumentation.retransformClasses` forbids adding/removing fields or methods —
so raw mixin output cannot be installed. The **schema-neutral converter** (`RetransformConverter`) rewrites each
mixin into a retransform-legal shape: added members are relocated to a same-package `$$LBSidecar`, added instance
fields become external per-instance state, and inaccessible-type/member access is routed through reflection
(`lbrt.AwReflect`). The result is byte-for-byte retransform-legal while preserving mixin behaviour.

`FullInjectAgent` (the `agentmain` entry point) drives it:

1. Detect the target loader and select a `LoaderPlatform` (vanilla / Fabric / NeoForge).
2. Stage the bundled LB classes + dependencies onto the target's classloader.
3. Acquire a Mixin transformer:
   - **vanilla** — boot a standalone Sponge-Mixin service (`vspike.VSpikeService`) with LB's configs +
     AccessWidener (bare vanilla has no mod loader to provide one);
   - **Fabric / NeoForge** — defer into the loader's own live Mixin service (Knot / FML).
4. For each target: run the transformer to get the mixin output `X`, diff against the original `O`, and convert
   `X` into a retransform-legal `target'` + `$$LBSidecar`.
5. `retransformClasses` the already-loaded targets; register a `ClassFileTransformer` for future ones.
6. Kick LB's bootstrap (`ClientStartEvent`) on the render thread.

### Architecture (the loader SPI)

`LoaderPlatform` is the single seam for loader-specific coupling (classloader, Mixin-service acquisition, class
definition, AccessWidener application, original-byte reads). Implementations:

- `VanillaPlatform` — bare vanilla via the standalone `vspike` Mixin service.
- `FabricPlatform` — stages LB onto `KnotClassLoader`, defers into `MixinServiceKnot`.
- `NeoForgePlatform` — stages LB onto FML's `TransformingClassLoader`, defers into `FMLMixinService`.

Adding another loader is a new `LoaderPlatform` — the converter and runtime helpers are loader-neutral.

### The CEF/shader lifecycle fix (in LB source, not here)

LB's custom render pipelines (JCEF blit, ClickGUI blur) are compiled inside MC's startup `ShaderManager.apply`,
which has already run by the time we attach. `ClientRenderPipelines.ensureCompiled()` (called from
`BrowserRenderer.render()`) compiles them on first draw with a `ClientShaders`-then-vanilla fallback source, with
a cache-reset retry if a lost race already poisoned the compile cache. This is what makes the CEF UI actually
composite on the late-attach path. Normal (mod-loader startup) rendering is unchanged.

## Layout

```
injector/
  src/main/java/          converter engine + LoaderPlatform SPI + platforms  (default pkg + lbrt.*)
  src/mixinservice/       vspike.* standalone Sponge-Mixin service (vanilla mixin engine) + its SPI meta
  src/lbpayload/          LB Platform SPI vanilla impl (net.ccbluex.liquidbounce.platform.vanilla) — bundled into LB
  tools/java/             Injector / InjectorUi — the standalone attach launcher
  lb-mixin-targets.txt    the mixin-target list the agent reads as a jar resource
```

## Build

```bash
# the attach agent (add -PbundleMcefNative for an offline/headless MCEF bundle)
./gradlew injectorAgentJar            # -> build/injector/liquidbounce-injector-agent.jar
# the standalone attach launcher
./gradlew injectorToolJar             # -> build/injector/liquidbounce-injector-tool.jar
```

The agent jar carries: the Mixin framework + `vspike` service at the root (system loader), the converter classes
+ `FullInjectAgent` at the root, `lb-mixin-targets.txt` + the AccessWidener as resources, and the LB payload (LB
classes + LB-owned deps) under `agent-libs/`. The manifest has **`Agent-Class` only** — it cannot be used as an
on-load `-javaagent`.

## Attach

```bash
# GUI: pick the target JVM, attach, tail the injection log
java -jar build/injector/liquidbounce-injector-tool.jar

# headless: attach directly to a PID
java -cp build/injector/liquidbounce-injector-tool.jar Injector <pid> \
     build/injector/liquidbounce-injector-agent.jar ""
```

The tool uses a separate process to attach (the standard model; no `jdk.attach.allowAttachSelf` needed). JDK 25
still permits dynamic attach (it prints a harmless "agent loaded dynamically" warning).

## Status

- **vanilla + Fabric**: late-attach injection of full LB — verified working on the reference branch
  (`feat/vanilla-agent`), including the CEF UI compositing on the converter path.
- **NeoForge**: `NeoForgePlatform` implemented and exercised on the reference branch (LB initializes; 13 of 151
  targets are Fabric-only mixins that skip cleanly).
- This branch reassembles that work onto current upstream `nextgen`, which carries a newer render subsystem; the
  shader fix has been re-derived against it and the module builds (`./gradlew injectorAgentJar`). A fresh
  late-attach run against this newer base is the outstanding verification step.
