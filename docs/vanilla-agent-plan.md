# LiquidBounce on vanilla Minecraft — Java-agent target (plan)

Branch: `feat/vanilla-agent` (off `feat/neoforge-support`, MC 26.2 multiloader base)

## Goal

Run **full LiquidBounce on unmodified vanilla Minecraft** — no Fabric, no NeoForge —
by injecting via a `-javaagent`. Keep the existing Fabric and NeoForge targets fully
working; vanilla is an *additive third target*, exactly like NeoForge was added
alongside Fabric.

Scope for the first milestone (PoC):

- Vanilla **client** only, MC 26.2.
- Full LB feature set running (modules, ClickGUI, config) — **except MCEF** (the
  Chromium browser UI) which is deferred; the ClickGUI falls back to its non-browser
  path for now.
- End state target: one codebase → **vanilla + fabric + neoforge**.

## Why this is tractable (verified against the codebase)

1. **No obfuscation on 26.1+.** Mojang ships the 26.2 client jar *deobfuscated*
   (real `net.minecraft.*` names, no `client_mappings` published — verified: on-disk
   jar sha1 matches the manifest `client` download, classes carry real names). The
   old "per-version obfuscation refmap" burden is **gone**. LB's mixins can target the
   vanilla jar directly with **no runtime remap**.
2. **Shared code is already loader-agnostic.** `src/main` has **0** `net.fabricmc` /
   `net.neoforged` imports. All loader coupling goes through a small `Platform`
   interface selected by `ServiceLoader`.
3. **`Platform` is tiny** — 7 methods: `gameDirectory`, `isModLoaded`,
   `hideModsFromModList`, `restoreModsInModList`, `removeModAndDeleteJars`,
   `buildCreativeTab`, `registerResourceReloadListeners`.
4. **Startup is already loader-independent.** LB boots from a mixin
   (`injection/mixins/minecraft/client/MixinMinecraft`), *not* a loader entrypoint —
   the Fabric `main` entrypoint list is empty. So init runs on vanilla as-is.
5. **Fabric-API surface is minimal** — creative-tab v1 + `FabricLoader`
   (mod-list / game-dir). That's the whole shim checklist.
6. **Mod-compat mixins already use `@Pseudo`** (Sodium/Lithium/Via/Truffle), so they
   tolerate their target classes being absent on vanilla instead of hard-failing.

The net-new engineering is therefore **the agent bootstrap** (a standalone Mixin
environment over `java.lang.instrument`) plus a **`VanillaPlatform`** and a couple of
vanilla-native shims. The ~150 shared mixins carry over unchanged.

## Architecture

New Gradle subproject `:vanilla` (sibling of `:neoforge`) that:

- Compiles `src/main` + a new `vanilla` source set against the **Mojmap vanilla jar**
  (no Fabric-loom remap step — identity mappings, since the jar is already named).
- Produces a **fat agent jar** bundling: LB classes, Kotlin stdlib, SpongePowered
  Mixin + MixinExtras, the JS engine (GraalJS/Truffle) and other LB runtime libs.
- Jar manifest declares `Premain-Class` / `Agent-Class`, `Can-Retransform-Classes`,
  `Can-Redefine-Classes`.

### Bootstrap flow (the core new piece)

```
premain(args, Instrumentation)
  1. Stand up a minimal Mixin service backed by Instrumentation
     (IMixinService + IMixinServiceBootstrap + IClassBytecodeProvider),
     modeled on agent-based loaders (e.g. Weave). No Knot/ModLauncher.
  2. MixinBootstrap.init(); set side = CLIENT; compatibilityLevel JAVA_25.
  3. Mixins.addConfigurations("liquidbounce.mixins.json",
                              "liquidbounce-vanilla.mixins.json")
  4. Register a ClassFileTransformer that pipes each class through the
     Mixin transformer before defineClass.
  5. Let Minecraft's main() proceed; MixinMinecraft fires → LiquidBounce.init().
```

Timing note: `premain` runs before MC's `main`, so MC classes are transformed on
first load (no need to retransform already-loaded classes, which has JVM limits).

### Mappings

None at runtime. Vanilla 26.2 == Mojmap == the names LB's NeoForge build already
compiles against. Build `:vanilla` mixins in their **dev/Mojmap form** and run them
directly. (This is effectively "Fabric dev mode, minus the Fabric loader".)

### Companion mixins

Vanilla uses the **unpatched vanilla method shapes** — i.e. the *Fabric* companion
shapes, not the NeoForge-patched ones (Hud split, BlockPos overloads). So
`liquidbounce-vanilla.mixins.json` starts as a copy of the **Fabric** companion set
(`MixinHud`, `MixinGui`, `MixinEntity`, `MixinPlayer`, `MixinModelBlockRenderer`,
`MixinLocalPlayer`, `MixinWeatherEffectRenderer`) compiled against Mojmap names.

### VanillaPlatform (the shim)

Implement the 7 `Platform` methods + register via
`META-INF/services/net.ccbluex.liquidbounce.platform.Platform`:

- `gameDirectory` — from launcher `--gameDir` / system property / cwd.
- `isModLoaded(id)` — `id in {"minecraft","liquidbounce"}` else false.
- `hideModsFromModList` / `restoreModsInModList` / `removeModAndDeleteJars` — no-op
  (return false); there is no mod list on vanilla.
- `buildCreativeTab` — vanilla-native (register via vanilla `CreativeModeTabs` /
  registry) or no-op stub for the PoC.
- `registerResourceReloadListeners` — hook the vanilla
  `ReloadableResourceManager` (small mixin/accessor) instead of the Fabric resource
  API. Needed for the theme/resource system.

## Build & launch

- `:vanilla:agentJar` → `liquidbounce-vanilla-<ver>.jar`.
- Launch: vanilla MC with `-javaagent:liquidbounce-vanilla.jar` (custom launcher
  profile / JVM arg). Kotlin stdlib + Mixin are inside the fat jar, so no extra
  classpath wrangling.
- Dev run task `:vanilla:runClient` that launches vanilla MC (via the piston client
  jar + libraries the loom cache already has) with the agent attached, on Xvfb.

## MCEF (deferred)

MCEF is loader-bound (its own Fabric/NeoForge artifacts). For the PoC the ClickGUI
uses its non-browser fallback. Later: package MCEF's native/runtime for the agent and
inject its init the same way, or provide a native UI. Tracked as a follow-up phase.

## Phased milestones

**P0 — Scaffolding.** Add `:vanilla` subproject, settings include, Mojmap MC
dependency wiring, empty fat-jar task. `./gradlew :vanilla:agentJar` produces a jar.
*Accept:* jar builds with correct manifest.

**P1 — Standalone Mixin bootstrap.** Implement the Instrumentation-backed Mixin
service + transformer; apply a trivial test mixin to a vanilla class and prove it
takes effect. *Accept:* a log line injected into `Minecraft.<init>` prints on a raw
vanilla launch under the agent.

**P2 — VanillaPlatform + service registration.** LB init runs to completion; no
`Platform` NPEs. *Accept:* LiquidBounce logs "started", ClickGUI opens.

**P3 — Apply the full mixin set.** Add `liquidbounce.mixins.json` + vanilla
companions; resolve any mixin that hard-references a loader/mod class not `@Pseudo`.
*Accept:* client boots into the main menu and a singleplayer world with 0 mixin apply
errors; modules toggle and function.

**P4 — Verify parity.** Record vanilla vs fabric side-by-side (reuse
`docs/minecraft-capture.md` pipeline). *Accept:* HUD, ClickGUI, and a handful of
modules behave identically (minus MCEF).

**P5 (later) — MCEF + tri-loader polish + creative tab.**

## Risks / open questions

- **Standalone Mixin service** is the main unknown — needs an `IMixinService` impl
  that doesn't assume a mod loader. Precedent exists (Weave, Vanilla-Mixin agents);
  validate against the exact Mixin version LB pins (`minVersion 0.8`, JAVA_25).
- **Kotlin + Truffle/GraalJS classloading** under a fat agent jar (system classloader
  vs app classloader) — GraalJS is picky about classloaders; may need the agent on the
  system classpath, not just `-javaagent`.
- **A few mixins may hard-reference loader classes** without `@Pseudo` — audit during
  P3; either add `@Pseudo`/`require=0` or move to a companion config.
- **Resource-reload + creative-tab** are the only Fabric-API behaviors without a
  drop-in vanilla equivalent; both are small.
- **Anticheat/launcher integrity** — `-javaagent` on vanilla is more detectable and a
  different UX than a mod. Out of scope for the PoC (correctness first).

## Non-goals (for the PoC)

MCEF/browser UI, server-side, older MC versions, distribution/launcher UX, obfuscated
(≤1.21.11) versions.
