# Self-contained NeoForge LiquidBounce agent

A **single `-javaagent` jar** that loads LiquidBounce into a stock NeoForge
client with **no Gradle, no dev mod, no staging, no `-Dlb.*` properties**. LB is
loaded *by the agent*, not installed as a mod — it never appears in FML's mod
list, yet its mixins are applied to `net.minecraft.*`.

This is the NeoForge sibling of `docs/fabric-agent-selfcontained/`. Same bar:
attach only the jar to a stock zero-Gradle NeoForge install → full LB init,
complete bundled deps, LB absent from the mod list, clean-room cold.

## Build

```
./gradlew :neoforge:neoforgeSelfContainedAgentJar
# -> neoforge/build/agent/liquidbounce-agent-neoforge.jar  (~54 MB, 50 bundled libs)
```

The task (in `neoforge/build.gradle.kts`) bundles, under `agent-libs/`:
- LB's compiled `:neoforge` classes + resources (`liquidbounce.jar`), and
- LB's full runtime dependency tree, enumerated **mechanically by maven group**
  (`keepGroups` — LB-owned groups only: `net.ccbluex` incl. `mcef-neoforge`
  (from `~/.m2`) + `fastutil4k`, kotlin/kotlinx, `ai.djl(.pytorch)`,
  `org.graalvm.*`, okhttp3/okio, junixsocket, ahocorasick, tika, lenni0451,
  vdurmont, thealtening, jagrosh).
  Platform/loader-provided groups (lwjgl, netty, mojang, asm, log4j, guava,
  neoforged.*, mixinextras — bundled *in* NeoForge — `net.jodah:typetools` which
  is NeoForge's eventbus dep, etc.) are **excluded**.
- `accesstransformer.cfg` at the jar root (LB's converted AccessWidener).

Manifest: `Premain-Class: nfagent.NFAgent`, `Can-Retransform-Classes: true`.

## How it works (`src/nfagent/`)

`premain` (self-contained, zero dev paths):
1. Locates *this* agent jar via its `ProtectionDomain`, extracts every
   `agent-libs/*.jar` + `accesstransformer.cfg` to a fresh temp dir
   (`deleteOnExit`). Sets `lb.at` to the extracted AT path.
2. Computes the **data-driven** `OWNED_PKGS` set — the package dir of every
   class in the bundle (no hand-maintained prefix list).
3. Repacks the bundle's **root-level resources** (the mixin config JSONs, which
   live at the jar root) into a resources-only jar and
   `appendToSystemClassLoaderSearch`es it, so `getSystemResourceAsStream` can
   read them (the TransformingClassLoader no longer has LB on its search).
4. `inst.addTransformer(new T(), true)`.

`T` (JDK `java.lang.classfile`, zero external ASM) hooks four seams, injecting
**before** the `ReturnInstruction` (constructor `atEnd` code would be
unreachable — a silent no-op):
- **Fallback + byte source** — on first sight of `TransformingClassLoader`:
  install `LbLoader` (child-first over the bundle, parent = TCL so LB sees
  `net.minecraft.*`) as the TCL's `fallbackClassLoader` (chained to FML's
  original `ResourceMaskingClassLoader`), **and** register every LB package into
  the TCL's `parentLoaders` map → `LbLoader`. The latter is essential: FML's
  mixin transformer reads class bytes via `getMaybeTransformedClassBytes`, which
  resolves by `parentLoaders`, **not** the fallback loader — without it,
  transforming a mixin target throws `ClassNotFoundException` on the LB mixin
  class during ASM frame computation.
- **AT** — `AccessTransformerService.<init>`: load the extracted
  `accesstransformer.cfg` into FML's live AT engine.
- **Mixin configs** — `MixinFacade.finishInitialization`: for each LB config,
  `((FMLMixinService)MixinService.getService()).addMixinConfigContent(cfg,
  ClassLoader.getSystemResourceAsStream(cfg).readAllBytes())` +
  `Mixins.addConfiguration(cfg)`.
- **Init trigger** — `AddClientReloadListenersEvent.<init>`: replicate the
  mod-bus dispatch so LB's `initializeClient` / `registerInbuilt` run during the
  blocking reload before first render.

`LbLoader` `owned()` is **data-driven**: a name is LB-owned iff its package dir
is in `OWNED_PKGS` (computed from the bundle) — no hand-grown prefix list. It is
child-first for owned packages (so LB's whole graph shares one kotlin runtime),
parent-first otherwise, with reentrancy guards on `loadClass`/`getResource(s)`.

## Verified (stock zero-Gradle launch — `selfcontained-stock-neoforge-*`)

Direct `java … net.neoforged.devlaunch.Main` with the platform classpath
(LB + all 50 LB-owned entries stripped out), `-Dfml.modFolders=` (LB not a dev
mod), and **only** `-javaagent:liquidbounce-agent-neoforge.jar`:

- Agent staged **50 bundled libs, 940 owned packages** out of the single jar.
- `Launching LiquidBounce v0.38.1 by CCBlueX`, `Loaded 36 Render Pipelines`,
  ConfigSystem/ThemeManager/ModelManager all initializing; LB classes served by
  `lb-agent-loader` from `liquidbounce.jar`.
- **No missing-dep crash** — every LB dependency resolved from the bundle.
- **LiquidBounce absent from FML's mod list**, yet its mixins apply to
  `net.minecraft.client.Minecraft`:
  `APP:liquidbounce.mixins.json:minecraft.client.{MinecraftAccessor,MixinMinecraft}
  from mod (unknown)` — `(unknown)` = agent-injected, not a registered mod.

Note: the in-world MCEF/GPU visual is VM-blocked (software GL + no network); the
one runtime exception is LB's own update-check hitting the network (404) on a
background thread — not a self-containment failure.
