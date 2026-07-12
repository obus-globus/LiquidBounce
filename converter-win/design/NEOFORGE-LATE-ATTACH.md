# Attaching LiquidBounce into an ALREADY-RUNNING NeoForge client — engineering design

Read-only investigation. Target: MC 26.2 / NeoForge 26.2.x, FML 11 (`net.neoforged.fml.classloading.*`).
Goal: reproduce, for a fully-booted NeoForge client (at the main menu OR joined to a server), what
`converter-win/src/FullInjectAgent.java` already does for a fully-booted **vanilla** client.

---

## 0. Framing: which problem this actually is

There are two NeoForge agents in the tree, and they solve **different** problems:

| Agent | Entry | When | MC target classes | Mixin capability | Converter needed? |
|--|--|--|--|--|--|
| `docs/neoforge-agent-selfcontained/src/nfagent/NFAgent.java` | `premain` | **launch time** (before MC loads) | **not yet loaded** | FML Mixin applies schema-changing mixins natively at define-time | **NO** |
| **THIS design** | `agentmain` (dynamic attach) | **after full boot** | **already loaded** by the TransformingClassLoader | load-time Mixin can no longer add/remove members on a loaded class | **YES** |

The existing NeoForge premain agent (`NFAgent`) is the *easy* case and is fully proven in-world
(`docs/neoforge-agent-probe/NEOFORGE-PROBE-RESULTS.md` §"(c) … GREEN"). It works precisely because
at premain the `TransformingClassLoader` has not yet defined `net.minecraft.*`, so registering LB's
configs into FML's live Mixin (`MixinFacade.finishInitialization` hook, `NFAgent.java:270-291`) lets
FML apply LB's 150+ mixins — including schema-changing ones — as those classes load.

**Late attach removes that.** Once `net.minecraft.client.Minecraft` et al. are defined,
`Instrumentation.retransformClasses` is the only re-shaping tool, and it **forbids adding/removing
fields, methods, or interfaces** (JVMTI redefinition constraints). LB's mixins overwhelmingly add
`@Unique` members and `@Shadow`/duck interfaces. So the vanilla solution — the **converter** — is
mandatory here too: transform each already-loaded target `O` → `X` (Mixin output), diff, and split
`X` into a retransform-legal `target'` (same schema as `O`) plus an external `$$LBSidecar` holding
the relocated state/handlers (`RetransformConverter` class javadoc, `RetransformConverter.java:6-20`).

`RetransformConverter` is **pure ASM and loader-agnostic** (`RetransformConverter.java:1-3`,
no `ClassLoader`/`Instrumentation`/system-loader references anywhere in the file except the injected
`CLASS_BYTES` byte-source function at `:858`). It ports to NeoForge **unchanged**. Everything that is
NeoForge-specific lives in the *host* — `FullInjectAgent` — which must be re-implemented against FML
seams. This document is the map from `FullInjectAgent`'s vanilla seams to their NeoForge equivalents.

---

## 1. Coupling map — every `FullInjectAgent` host seam → NeoForge equivalent

`FullInjectAgent` pins itself to the **system classloader** (`SYS = ClassLoader.getSystemClassLoader()`,
`FullInjectAgent.java:19`) because on bare vanilla LB, MC, and the agent all live on the system loader.
On NeoForge none of that holds. The seams:

### 1a. Which classloader holds `net.minecraft.*`

**Vanilla:** system loader. `SYS` is used for byte reads (`:51`, `:109`), `Class.forName(…, SYS)`
(`:88`, `:199`, `:219`, `:225`…), and `defineClass` into `SYS` (`:273`).

**NeoForge:** `net.neoforged.fml.classloading.transformation.TransformingClassLoader` (TCL), and MC is
in a **named module `minecraft`**. Proven empirically by the probe:
`loader=net.neoforged.fml…TransformingClassLoader module=minecraft`
(`docs/neoforge-agent-probe/NEOFORGE-PROBE-RESULTS.md:34-35`, and `NFAgent.TCL` constant
`NFAgent.java:38`). The TCL resolves classes by an internal **`parentLoaders` (package→loader) map**
and has a single **`fallbackClassLoader`** slot.

**How `LbLoader` gets LB onto that loader, and can we reuse it for sidecars:**
`NFAgent` installs `LbLoader` (a child-first `URLClassLoader` whose *parent is the TCL*) as the TCL's
`fallbackClassLoader` (`NFAgent.java:208-219`, via reflective `setFallbackClassLoader`) **and**
registers every LB package into `parentLoaders` → `LbLoader` (`registerParentLoaders`,
`NFAgent.java:163-186`). The second step is the important one: FML's mixin byte-reader
(`getMaybeTransformedClassBytes`) resolves by `parentLoaders`, not the fallback
(`NFAgent.java:216-219`, README `neoforge-agent-selfcontained/README.md:50-58`).

We **reuse this exact mechanism** for sidecars, but with a twist forced by the module system (§1d):
the generated `$$LBSidecar`/`$State` bytes are **defined by `LbLoader`** (into an LB-owned package),
and that package is registered in `parentLoaders` so a `target'` running on the TCL can resolve its
sidecar by name across the loader boundary. `LbLoader`'s parent = TCL means the sidecar sees the same
`net.minecraft.*` `Class` objects the target uses — no split-class identity. This is the NeoForge
analogue of vanilla's `define(dotted, bytes, pd)` into `SYS` (`FullInjectAgent.java:273`).

### 1b. Reading an already-loaded MC class's ORIGINAL bytes `O`

**Vanilla:** `SYS.getResourceAsStream(internal + ".class")` (`:51`, `:109`). On bare vanilla the jar
bytes *are* the pristine baseline (no loader transforms), so a jar read == `O`.

**NeoForge — do NOT read from a jar.** The TCL applies **AccessTransformers + coremods + other mods'
mixins** at define time; `getResourceAsStream` on a `net/minecraft/...class` returns the **raw module
jar bytes** (pre-AT, pre-coremod, pre-other-mods), which is *not* the baseline the loaded class was
actually defined from. Diffing `X` against raw jar bytes would misclassify NeoForge's own AT-widened
members / coremod edits as "LB-added" and corrupt the schema split.

**The correct `O` is the JVM's current authoritative form of the class**, which the platform hands us
for free:
- **already-loaded target** → `O` = the `byte[] buf` passed to our `ClassFileTransformer.transform`
  during `Instrumentation.retransformClasses(target)`. That buffer is the current loaded form
  (post-AT, post-coremod, post-other-mixins, **pre-LB**). This mirrors how vanilla's CFT already
  rebases onto the JVM-supplied buffer (`RetransformConverter.rebase`, `RetransformConverter.java:852`,
  used at `FullInjectAgent.java:169`).
- **future-loaded target** → `O` = the incoming `buf` in the load-time CFT (same as vanilla `:190`).

So the NeoForge byte-source wired into `RetransformConverter.CLASS_BYTES` (`:858`) — used by the
converter only for **hierarchy metadata** (`superOf`/`commonSuper`/`nonPublicField`, `:882-923`), not
for `O` itself — should resolve `net/minecraft/*` through the **TCL** (`tcl.getResourceAsStream`),
which is fine for hierarchy/`superName` reads even if it returns raw bytes (superclass/interfaces are
not AT-affected). `O` for the actual diff always comes from the retransform buffer.

**Naming (dev==prod, no refmap):** MC 26.2 runs on **Mojmap/official names at runtime on NeoForge**,
so LB's dev names == prod names and **no refmap is needed** — verified and stated in
`agent-injection-productionization-plan.md:135-138` and `NEOFORGE-PROBE-RESULTS.md:129-131`. This is
what makes the loader-agnostic converter viable: the sidecar/`target'` member names the converter
emits match the runtime class exactly, identically to vanilla.

### 1c. Obtaining `X` (Mixin output) for a target

**Vanilla:** stands up its **own standalone Sponge service** (`vspike.VSpikeService`), boots Mixin
(`MixinBootstrap.init()`, `FullInjectAgent.java:74`), registers LB configs (`:75`), and calls
`tr.transformClassBytes(name,name,O)` on its own transformer (`:80`, `:110`). It can do this because
bare vanilla has *no* competing Mixin service.

**NeoForge — DEFER, do not stand up a second service.** Mixin's `MixinService.getService()` is a
process-global singleton; on NeoForge it is already `FMLMixinService`
(`NEOFORGE-PROBE-RESULTS.md:84`). Standing up a second service is the exact COEXIST failure ruled out
in `agent-on-all-loaders-plan.md:47-54`. Instead:

1. Register LB's configs into FML's live service — the **already-proven** premain path, reused at
   agentmain: `((FMLMixinService)MixinService.getService()).addMixinConfigContent(cfg, bytes)` +
   `Mixins.addConfiguration(cfg)` (`NFAgent.emitRegister`, `NFAgent.java:334-344`; probe evidence
   `NEOFORGE-PROBE-RESULTS.md:70-108`). At agentmain we call this **directly** (no bytecode hook of
   `MixinFacade.finishInitialization` needed — that method already ran at boot).
2. Acquire FML's **live `IMixinTransformer`** and call `transformClassBytes(name,name,O)` on it to get
   `X`, exactly as vanilla calls its own (`FullInjectAgent.java:110`). Sponge's `IMixinService`
   exposes the transformer; `FMLMixinService` holds the one FML created. The vanilla code already
   imports `org.spongepowered.asm.mixin.transformer.IMixinTransformer` (`FullInjectAgent.java:7`) and
   obtains it via `MixinService.getService()` cast (`:80`) — the same call shape works, casting to
   `FMLMixinService` instead of `VSpikeService`.

**This is a genuine unknown (see Risk #2).** The premain path only ever let FML apply mixins at
class *define* time; invoking FML's transformer on-demand for an *already-loaded, post-INIT-phase*
class, after the environment has been committed to `DEFAULT`, is not something the probe exercised.
The transformer may refuse to (re)select configs once `transformedCount>0`, or may require the
environment to still be selecting. Milestone M1 (§5) exists to de-risk exactly this.

### 1d. ProtectionDomain / module system — the hardest seam

**Vanilla:** defines the sidecar/state into `SYS` **with the target's exact `ProtectionDomain`**
(`Conv.define(pd)`, `FullInjectAgent.java:36-42`; eager path `:139-140` uses
`loadedMap.get(internal).getProtectionDomain()`). The PD match matters because vanilla puts the
sidecar **in the target's own package** to keep package-private access
(`RetransformConverter.java:40-43`), and a signed MC package requires signer-matching PD to define a
new member class into it.

**NeoForge — two hard obstacles, and the design that dodges both:**

*Obstacle A — JPMS package ownership.* `net.minecraft.client.multiplayer.chat` is owned by the named
module `minecraft`. A package is owned by **exactly one module per loader**. We cannot define
`net/minecraft/.../GuiMessage$$LBSidecar` (same package as the target) into that named module from
outside: a reflective `defineClass` on the TCL/ModuleClassLoader either rejects it or files it under
the **unnamed** module → then module-`minecraft`'s `net.minecraft.client…chat` and unnamed's
`net.minecraft.client…chat` are **different runtime packages** → `IllegalAccessError` on every
package-private access **and** a split-package hazard. Vanilla's "sidecar-in-target-package" trick is
therefore **not available** on NeoForge.

*Obstacle B — module encapsulation for our own reflection.* FML internals
(`net.neoforged.fml.*`, the `parentLoaders` field, `FMLMixinService`) are in named, non-open modules;
reflective access needs opens.

**Design:** place sidecars in an **LB-owned package** (e.g. `net/ccbluex/lbrt/sidecar/<mangled>`),
define them via **`LbLoader`** (unnamed module, child of TCL), and register that package in
`parentLoaders` so `target'` (on TCL) resolves the sidecar by name (§1a). Consequences:

- PD is trivially `LbLoader`'s own (LB jar) — **no signer constraint**, because the sidecar is no
  longer in a signed MC package. This *removes* the vanilla PD-matching complexity
  (`Conv.define(ProtectionDomain)` collapses to a plain `LbLoader.define(name,bytes)`).
- The sidecar is **not** a nestmate/package-mate of the target, so it loses package-private and
  protected access. **Every non-public MC member touched by a relocated handler must route through
  reflection**, not just genuinely-`private` ones. The converter **already supports this** — it has
  the cross-package reflection paths: `nonPublicField(...)` → `rewriteFieldAw` for inherited
  non-public fields (`RetransformConverter.java:459-471`), and `illegalSidecarMethod(...)` →
  `rewriteAwMethodInline`/`rewriteAwCtorInline` for methods/ctors the relocated body loses access to
  (`:490-499`, `:887-893`). The only change is **wiring the `Resolver`** so it declares *all*
  non-public members of MC targets reflect-needed (see §1e). We trade vanilla's package-private
  optimization for correctness; net effect is *more* reflection, but `AwReflect` is unchanged and
  already handles it.
- The sidecar's `IdentityHashMap` state store and static `@Unique` fields live on the sidecar
  (`LbLoader`) — no module concern.

This is the crux and the honest #1 risk (§5). It is more moving parts than vanilla's single-loader
`defineClass`, and the cross-loader `parentLoaders` resolution of `target'`→sidecar must be exercised
early.

### 1e. AccessTransformer vs AccessWidener

**Vanilla:** LB ships an **AccessWidener**; the agent loads it into `vspike.AccessWidener`
(`FullInjectAgent.java:72`) and:
- applies it **on-load** to future MC classes in the CFT (`awApply`, `:190`, `:386`);
- for **already-loaded** classes it cannot widen (retransform bans modifier changes), so it computes
  the `INACC` set (already-loaded + still-non-public AW targets, `:84-90`) and routes LB's access to
  those members through **`AwReflect`** reflection (`Resolver` at `:151-155`,
  `RetransformConverter.rewriteLbAw`, `RetransformConverter.java:740`).

**NeoForge:** LB uses an **AccessTransformer** (`accesstransformer.cfg`, generated from the AW at
build time by `ConvertAccessWidenerTask`, `neoforge/build.gradle.kts:81-91`). The premain agent feeds
it into FML's live `AccessTransformerEngine` (`AccessTransformerService.<init>` hook,
`NFAgent.java:238-266`). The "widen or reflect" mapping is **structurally identical to vanilla**:

| Class state at attach | Vanilla | NeoForge |
|--|--|--|
| **future-loaded** MC class | AW applied on-load in CFT (`awApply`) | AT already active in FML's engine (registered at attach via the `loadAT` call, `NFAgent.java:238`), applied by FML on define — **or** applied by our CFT as a fallback |
| **already-loaded** MC class | cannot widen → `AwReflect` reflection | cannot AT-widen post-load (**same JVMTI constraint**) → `AwReflect` reflection — **identical** |

Key point: **for already-loaded classes, AT and AW are equivalent no-ops post-load, and `AwReflect`
covers both identically** — reflection with `setAccessible(true)` ignores AT/AW/modifiers entirely
(`AwReflect.java:22-24` `f.setAccessible(true)`). So `AwReflect.java` **drops in unchanged**. What
changes is only how "which members are non-public/inaccessible right now" is *computed*: vanilla reads
its `vspike.AccessWidener.classAccessible` set (`:86-88`); NeoForge must instead ask the **live loaded
class** via reflection (`Class.forName(name,false,tcl)` then `Modifier.isPublic`), because the AT's
effect on a *future* class isn't yet reflected in a widener object we own. Since sidecars are
cross-package anyway (§1d), the simplest correct `Resolver` is:

```
fieldNeedsReflect(o,n,d)  = isMcClass(o) && (alreadyLoaded(o) ? fieldNonPublicLive(o,n)
                                                              : fieldNonPublicInBaseline(o,n));
methodNeedsReflect(o,n,d) = isMcClass(o) && (alreadyLoaded(o) ? methodNonPublicLive(o,n,d)
                                                              : methodNonPublicInBaseline(o,n,d));
typeInaccessible(in)      = isMcClass(in) && nonPublicType(in);   // AT may make some public on load
```

This is the same `Resolver` interface vanilla already defines (`RetransformConverter.java:728-732`,
wired at `FullInjectAgent.java:151-155`); only the predicate bodies change source (live class vs
widener set). For future-loaded classes where the AT *does* make a member public, FML's engine widens
it and `methodNonPublicInBaseline` (reading the raw jar) would over-reflect — acceptable (reflection
still works), or refine by consulting the parsed AT. Reflection is always the safe fallback.

---

## 2. Bootstrap / init on NeoForge, and what late attach must manually kick

**Normal NeoForge init (two independent triggers):**
1. **`ClientStartEvent`** fired by `MixinMinecraft` — LB's cross-loader bootstrap, "identical to the
   Fabric distribution" (`LiquidBounceNeoForge.java:30-31`). This is the same event vanilla's late
   agent manually re-fires (`FullInjectAgent.java:225-235`).
2. **Mod-bus `AddClientReloadListenersEvent`** → `NeoForgePlatform.onAddReloadListeners(event)`
   (`LiquidBounceNeoForge.java:37-39`, `NeoForgePlatform.kt:150-155`), which registers LB's
   `LazyReloadListener` wrappers so `initializeClient()` (module registration + theme/MCEF) runs
   during the initial blocking resource reload, **before the first render frame**
   (`NeoForgePlatform.kt:100-115`, `registerResourceReloadListeners` returns `false` when the wrappers
   aren't bound, triggering LB's direct-reload fallback).

**Late attach must kick BOTH, and the ordering is the whole game:**

- Both triggers already fired (and were lost) at real startup, long before we attached. So, exactly as
  vanilla does, we must **manually re-dispatch `ClientStartEvent`** on the MC main thread
  (`FullInjectAgent.java:218-247`: `Minecraft.execute(kick)`, `EventManager.callEvent(ClientStartEvent)`).
  On NeoForge the same reflective sequence works (LB's event system is shared, loader-agnostic).
- The **reload-listener path is unreachable at late attach** — the initial resource reload is long
  over. We must invoke LB's **direct init fallback** instead: because `registerResourceReloadListeners`
  returns `false` when no wrappers are bound (`NeoForgePlatform.kt:108-114`), LB's own fallback runs
  `initializeClient()` → `ModuleManager.registerInbuilt()` directly. The late agent must ensure that
  fallback path executes (it will, since we never call `onAddReloadListeners`, so no wrappers bind).
  This is the **root fix** the probe landed for the in-world crash
  (`NEOFORGE-PROBE-RESULTS.md:186-198, 221-240`) — and it maps cleanly to late attach.

**Readiness-gated activation (the anti-crash discipline, critical here):** vanilla bootstraps LB to
`LiquidBounce.isInitialized() == true` **before** it retransforms/activates *any* already-loaded
target (`FullInjectAgent.java:216-269`, `restoreRegistriesAfterInitialization` waits on
`isInitialized` at `:347-360`, then `activateLoadedTargets` at `:251-270`). This exists because
activating hooks mid-init lets render/entity hooks fire before Kotlin singletons finish, circularly
initializing them. On NeoForge this is **exactly the in-world crash the probe hit**
(`nf-agent-inworld-crash.log`; §4). The late agent must keep this ordering: **init LB fully, then
publish target retransforms.**

**JoinGate / registry-thaw — applicable, unchanged.** If the client is **already in a world**, LB init
touches frozen registries; vanilla thaws them transactionally around the kick
(`RegistryThawSession`, `FullInjectAgent.java:305-345`) and defers any in-flight server-join via
`JoinGate`/`JoinGateRewriter` on `ConnectScreen.startConnecting` (`JoinGate.java`,
`JoinGateRewriter.java:6`). NeoForge uses the **same** `net.minecraft.core.MappedRegistry.frozen`
field and the **same** `net/minecraft/client/gui/screens/ConnectScreen` (Mojmap names hold), so both
components port **unchanged** — the only adjustment is that `JoinGateRewriter.rewrite` output must be
published via the NeoForge CFT/retransform path (a `target'`-style edit) rather than the vanilla one.

---

## 3. Reusable (drop-in) vs new (NeoForge Platform)

### Drop in UNCHANGED (loader-agnostic)
- **`RetransformConverter.java`** — pure ASM; only needs `CLASS_BYTES` pointed at a NeoForge byte
  source (`:858`). All schema-split / sidecar / reflection-lowering logic is host-independent.
- **`AwReflect.java`** — reflection with `setAccessible`; ignores AT/AW/modules. *One line to review:*
  its private `SYS = getSystemClassLoader()` (`AwReflect.java:12`) must become the **TCL**, because
  the MC classes it resolves live on the TCL, not the system loader. Parameterize the loader.
- **`lbrt/DuckDispatch`** (interface duck-typing), **`lbrt/JoinGate.java`** (same `SYS→TCL` caveat,
  `JoinGate.java:8`), **`JoinGateRewriter.java`**, **`LateAttachVerifier.java`** (byte-level
  verification, no loader refs), **`AccessorBridgeRewriter`** — all loader-agnostic ASM/reflection.
- The **transactional convert-verify-publish gate** logic in `FullInjectAgent` (phase A/B,
  `:104-145`) — the *algorithm* is reusable; only the byte-source and define/retransform primitives
  it calls are NeoForge-swapped.

### NEW — a `NeoForgePlatform` host (replaces `FullInjectAgent`'s vanilla plumbing)
A small abstraction with these operations, most of which **already exist as premain code to lift**:
1. **Classloader access** — discover the TCL, install `LbLoader`, register `parentLoaders`. **Lift
   verbatim** from `NFAgent.registerParentLoaders`/`readField`/the `T.transform` fallback install
   (`NFAgent.java:163-223`) and `LbLoader.java` (whole file). At agentmain the TCL is already created,
   so instead of waiting for it in a CFT we find it directly (walk `getAllLoadedClasses` for a
   `net.minecraft.*` class and take its `getClassLoader()`), then install once.
2. **Mixin acquisition** — register LB configs into `FMLMixinService` (lift `NFAgent.emitRegister`
   logic, `:334-344`, called directly instead of injected) + obtain the live `IMixinTransformer`
   (**new**, Risk #2).
3. **Class definition into a module** — define `$$LBSidecar`/`$State` via `LbLoader` + parentLoaders
   registration (**new**, §1d). Replaces `FullInjectAgent.define`/`defineSynthetics` (`:273-298`).
   Note `defineSynthetics` (Mixin `org.spongepowered.asm.synthetic.*` generation) is **free on
   NeoForge** — the TCL/FML already generate them (`agent-on-all-loaders-plan.md:86-89`), so the
   vanilla synthetic-generation dance (`:279-303`) largely disappears.
4. **AT application** — feed `accesstransformer.cfg` into FML's `AccessTransformerEngine` for future
   classes (lift `NFAgent.hookAt`'s `engine.loadAT(reader,"liquidbounce")` call, `:238-266`, invoked
   directly on the live engine at agentmain) + the live-class `Resolver` for already-loaded ones (§1e).
5. **Module opens** — `Instrumentation.redefineModule` to open `net.neoforged.fml.*` (and
   `java.lang`) to the agent module for reflection (vanilla already opens `java.lang`,
   `FullInjectAgent.java:49`; NeoForge needs the FML modules too).
6. **Init kick + readiness gate + registry thaw + JoinGate** — reuse vanilla's orchestration
   (`FullInjectAgent.java:216-270, 305-385`) with the NeoForge init fallback of §2.

### Payload (build) — reuse the self-contained NeoForge agent jar recipe
`neoforge/build.gradle.kts:339-360` already bundles LB classes (`liquidbounce.jar`), the LB-owned dep
tree by maven group (`keepGroups`, `:348-353`), and `accesstransformer.cfg` at root — excluding
NeoForge-provided libs (ModLauncher, mixinextras, ASM, log4j…). The late-attach agent reuses this jar
layout; manifest gains `Agent-Class`/`Can-Retransform-Classes`/`Can-Redefine-Classes` (it already has
`Premain-Class` + `Can-Retransform-Classes`, `:345`).

---

## 4. Attach mechanics + module-system obstacles

### Dynamic attach into a running NeoForge JVM
- Entry is **`agentmain(String,Instrumentation)`** (vanilla `FullInjectAgent.agentmain`, `:44`), loaded
  via `VirtualMachine.attach(pid).loadAgent(jar)`. Self-attach needs
  `-Djdk.attach.allowAttachSelf=true` (disabled by default since JDK 9,
  `agent-injection-productionization-plan.md:313-316`); external attach (separate launcher process)
  avoids that flag. NeoForge's own dev launch uses `DevAgent`/`SelfAttach`
  (`agent-injection-productionization-plan.md:166`) — a proven attach pattern to mirror.
- `Instrumentation.retransformClasses` and `redefineClasses` are both **supported=true** on NeoforGe's
  TCL — proven by the probe (`NEOFORGE-PROBE-RESULTS.md:32, 48-49`), and `addTransformer(t,true)`
  fires for TCL-defined classes (`:44-47`). So the vanilla CFT+retransform machinery
  (`FullInjectAgent.java:159-192`, `activateLoadedTargets` `:251-270`) has a working substrate.

### Module-system obstacles (rank-ordered)
1. **Sidecar package ownership** (§1d) — cannot define into module `minecraft`; must cross to
   `LbLoader` via `parentLoaders`. **Hardest.**
2. **Reflective access into FML internals** — `parentLoaders`/`fallbackClassLoader` fields
   (`NFAgent.java:163-186, 208-212`) and `FMLMixinService` are in named modules. At **premain** the
   agent reads them without opens because the module graph isn't sealed yet; at **agentmain** the
   graph is sealed, so we need `inst.redefineModule(fmlModule, …, opens: {pkg → agentModule}, …)`.
   `redefineModule` can add opens to an already-defined module at runtime — this is the intended API
   and is available to a dynamic agent. Feasible but must be done for every FML package we reflect on.
3. **`retransformClasses` vs FML's own transforms** — on FML 11 the class-transformation pipeline runs
   at ModuleClassLoader **define** time, *not* as `java.lang.instrument` transformers. So a retransform
   re-runs only **instrument** transformers (ours), over the JVM's **current** bytes — it will **not**
   re-apply FML mixins/AT (they're baked into the loaded form already). That is exactly what we want
   (`O` = current form, we add LB on top). **Must be verified** (M1), since a surprise re-entry of
   FML's transformer during retransform would double-apply.
4. **TransformingClassLoader isolation** — our agent classes load on the system loader and cannot see
   `net.minecraft.*`/FML directly; every touch goes through reflection or `LbLoader`. `LbLoader`'s
   parent=TCL bridge (`LbLoader.java:31-34`) is the sanctioned crossing and already handles the
   reentrancy loops (guards at `LbLoader.java:55-67, 74-101`).

### The in-world crash (`nf-agent-inworld-crash.log`) — factored in
The log shows a **circular Kotlin `object` `<clinit>`**: `ModuleSwordBlock.shouldHideOffhand`
(a render/tick hook) touches `KillAuraAutoBlock`, whose `<clinit>` runs `ModuleKillAura.<clinit>`,
which reads the half-constructed `KillAuraAutoBlock` as null → `ValueGroup.tree(... valueGroup=null)`
NPE (`nf-agent-inworld-crash.log:2-15`, matching `NEOFORGE-PROBE-RESULTS.md:157-170`). Root cause was
**init timing**: with no ModFile the mod-bus reload-listener registration was skipped, so
`initializeClient` never ran at the right time and module singletons initialized in the wrong order
under a live render loop. The probe fixed it by replicating `onAddReloadListeners` at premain
(`NEOFORGE-PROBE-RESULTS.md:221-240`).

**For late attach the same failure mode is *more* acute** — we attach into a client that is *already
rendering/ticking*, so the instant we activate (retransform) `ModuleSwordBlock`'s target, its hook can
fire before `ModuleKillAura` finishes initializing. This is precisely why vanilla's **readiness gate**
exists: it forces `LiquidBounce.isInitialized()` before publishing *any* target retransform
(`FullInjectAgent.java:347-371`). The NeoForge late agent **must** honor that ordering AND run LB's
direct-init fallback (§2) so module singletons are fully constructed before the first hook goes live.
The render-path "Missing uniform Globals" crash (`NEOFORGE-PROBE-RESULTS.md:207-209`) is a separate,
agent-specific rendering-integration gap that was ultimately resolved for premain but is **untested
under late attach** and is a residual risk (Risk #3).

---

## 5. Feasibility verdict, risks, and phased build plan

### Verdict
**Feasible, and the hardest of the three loaders — but not blocked by any single impossibility.** The
load-bearing asset is that the **converter core is loader-agnostic and already proven at scale on
vanilla** (146/146 targets, per the branch's commit log), and every NeoForge-specific seam has a
concrete, evidence-backed mapping above — most of them **lifted from the already-working premain
`NFAgent`** (classloader install, config registration, AT engine feed, init replication). The three
genuinely new elements are (a) sidecar placement across the `LbLoader`↔TCL boundary under JPMS,
(b) driving FML's *live* `IMixinTransformer` on-demand for already-loaded classes, and (c) preserving
the readiness-gated activation ordering against a live render loop. None is a known wall; each is an
empirical unknown with a cheap probe. **The honest caveat: this is materially more integration surface
than vanilla, and NeoForge's own in-world integration gaps (render path, MCEF) were whack-a-mole even
at premain (`NEOFORGE-PROBE-RESULTS.md:200-219`) — late attach reopens those against a running world.**

### Top 3 risks (ranked; module-system obstacle rated honestly as #1)

1. **[HIGHEST] JPMS module ownership + cross-loader sidecar identity.** We cannot define
   `$$LBSidecar` into module `minecraft` (package owned by one module), so sidecars must live in an
   LB package on `LbLoader` and be resolved by `target'` (on the TCL) through `parentLoaders`
   (§1d, `NFAgent.java:163-186`). Unknowns: (i) does a TCL-loaded `target'` reliably resolve an
   LB-package sidecar via `parentLoaders` when the call originates from a *retransformed* class?
   (ii) all non-public MC access degrades to `AwReflect` (perf + correctness across the whole 146
   targets, vs vanilla's package-private fast path). This is more fragile and less optimized than
   vanilla's single-loader `defineClass(SYS, …, targetPD)` and is the most likely place to break.

2. **[HIGH] Acquiring and driving FML's live `IMixinTransformer` at agentmain.** Vanilla owns its
   transformer (`FullInjectAgent.java:80`); NeoForge must borrow FML's, post-`DEFAULT`-phase, and call
   `transformClassBytes` on already-loaded classes. Mixin's lazy config selection may have closed
   (`transformedCount>0`), or the transformer may not re-select LB's late-added configs for an
   already-transformed class. If FML's transformer won't produce `X` on demand, the fallback is
   standing up a *second, isolated* transformer that shares FML's config/AW view without registering a
   rival service — unproven and possibly the COEXIST trap (`agent-on-all-loaders-plan.md:47-54`).

3. **[HIGH] Init-timing / in-world circular-init + render race under a live world.** The
   `ModuleKillAura` NPE (`nf-agent-inworld-crash.log`) and "Missing uniform Globals" render crash are
   the documented NeoForge in-world failure modes. Late attach fires LB init and activates hooks into
   an *already-rendering* client, tightening the window the readiness gate must protect
   (`FullInjectAgent.java:347-371`). The premain fix (reload-listener replication) doesn't transfer
   verbatim — the initial reload is over — so we rely on LB's direct-init fallback + strict
   init-before-activate ordering, unverified under late attach.

Lesser risks: retransform re-entering FML's own transforms (§4.3, verify M1); self-attach JVM-flag /
anticheat-shape/ToS concerns (`agent-injection-productionization-plan.md:313-318`); `@Local`/named-LVT
mixin fragility against a non-dev client jar (`agent-injection-productionization-plan.md:326-328`).

### Phased build plan (smallest provable milestone → full)

- **M0 — Attach + classloader install (no mixins).** `agentmain` attaches to a running NeoForge
  client; find the TCL from a loaded `net.minecraft.*` class; install `LbLoader` + `parentLoaders`
  (lift `NFAgent`); `redefineModule` opens for FML packages. Prove: LB class loads via `LbLoader`, and
  a trivial LB-package class is resolvable **from** a TCL-loaded MC class. *De-risks Risk #1's loader
  crossing, cheaply, with no converter.*
- **M1 — One already-loaded target, converted + retransformed.** Register LB configs into
  `FMLMixinService`; acquire FML's `IMixinTransformer`; pick ONE simple already-loaded schema-changing
  target (e.g. a `MinecraftAccessor`-style target); compute `O`(retransform buffer)→`X`, run
  `RetransformConverter`, define the sidecar via `LbLoader`, `retransformClasses` the target, verify
  via `LateAttachVerifier`. Confirm FML's transforms do **not** re-run on retransform (§4.3). *De-risks
  Risk #2 (live transformer) and Risk #1 (sidecar define/resolve) on a single class.*
- **M2 — Full converter pass at the main menu.** All 146 targets, transactional convert-verify-publish
  gate (port `FullInjectAgent` phase A/B), `AwReflect` for all non-public MC access via the live-class
  `Resolver`, AT fed to FML's engine for future classes. Prove: no schema/verify failures, client
  stays at menu healthy. *Mirrors the vanilla "146/146 clean" milestone on NeoForge.*
- **M3 — LB bootstrap at the menu.** Manual `ClientStartEvent` kick + LB direct-init fallback +
  readiness gate before publishing target retransforms; JoinGate installed. Prove: `Launching
  LiquidBounce`, `isInitialized()==true`, MCEF menu, a module (Fly) togglable — the late-attach analogue
  of the premain menu result (`selfcontained-stock-neoforge-evidence.md:24-29`).
- **M4 — Attach while already in a world.** Registry-thaw around init; activate hooks under a live
  render/tick loop with the readiness gate; resolve the `ModuleKillAura` circular-init + render-race
  (Risk #3). Prove: Fly works in-world, no NPE/render crash — the hardest gate, and the one most likely
  to need iteration.

**What might NOT be feasible / where to stop honestly:** if M1 shows FML's live transformer refuses
on-demand `transformClassBytes` for already-loaded classes (Risk #2) with no non-COEXIST workaround, or
if M4's render race proves unfixable under late attach, the honest fallback is **"NeoForge late attach
supports menu-time (M3) but not in-world (M4)"**, or **"attach only works before world entry."** The
menu-time capability alone still matches the vanilla feature bar for the common case (attach at menu,
then join).
