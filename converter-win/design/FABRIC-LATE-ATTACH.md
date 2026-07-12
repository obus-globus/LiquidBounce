# Attaching LiquidBounce into an ALREADY-RUNNING Fabric client — engineering design

Read-only investigation. Target: MC 26.2 / Fabric Loader **0.19.3** (verified in the gradle cache:
`net.fabricmc/fabric-loader/0.19.3`), Knot classloader + `MixinServiceKnot`. Goal: reproduce, for a
fully-booted Fabric client (at the main menu OR joined to a server), what
`converter-win/src/FullInjectAgent.java` already does for a fully-booted **vanilla** client.

All byte-level Fabric-loader facts below were confirmed by disassembling
`fabric-loader-0.19.3.jar` (`javap -p`) — cited inline as `[javap]`.

---

## 0. Framing: which problem this actually is

There are two Fabric agents in the tree, and they solve **different** problems:

| Agent | Entry | When | MC target classes | Mixin capability | Converter needed? |
|--|--|--|--|--|--|
| `docs/fabric-agent-selfcontained/src/scagent/{SCAgent,SCHook}` and `docs/fabric-agent-spike/lb-agent/src/lbagent/{LBAgent,LBHook}` | `premain` | **launch time** (before MC loads) | **not yet loaded** | Knot's `MixinServiceKnot` applies schema-changing mixins natively at define time | **NO** |
| **THIS design** | `agentmain` (dynamic attach) | **after full boot** | **already loaded** by KnotClassLoader | load-time Mixin can no longer add/remove members on a loaded class | **YES** |

The existing Fabric premain agent is the *easy* case and is fully proven in-world
(`docs/fabric-agent-spike/FABRIC-SPIKE-RESULTS.md`: `Launching LiquidBounce v0.38.1`, Fly +51 blocks,
LB absent from the 116-mod list). It works precisely because at premain KnotClassLoader has not yet
defined `net.minecraft.*`, so `SCHook.onReady` registering LB's configs into the **live**
`MixinServiceKnot` (`Mixins.addConfiguration(...)`, `SCHook.java:70-71`) lets Knot apply LB's 150+
mixins — including schema-changing ones — as those classes load. The proven mechanics are exactly the
three calls in `SCHook.java:55-71`:
`FabricLauncher.addToClassPath(lbJar,"net.ccbluex")` → `ClassTweakerReader.create(FabricLoaderImpl.INSTANCE.getClassTweaker()).read(aw,"official")` → `Mixins.addConfiguration(...)`.

**Late attach removes that.** Once `net.minecraft.client.Minecraft` et al. are defined,
`Instrumentation.retransformClasses` is the only re-shaping tool, and it **forbids adding/removing
fields, methods, or interfaces**. So the vanilla solution — the **converter** — is mandatory for the
already-loaded target subset: transform each already-loaded target `O` → `X` (Mixin output), diff,
split `X` into a retransform-legal `target'` (same schema as `O`) plus an external `$$LBSidecar`
holding relocated state/handlers (`RetransformConverter.java:6-20`).

`RetransformConverter` is **pure ASM and loader-agnostic** (`RetransformConverter.java:1-3`; the only
external coupling is the injected `CLASS_BYTES` byte-source function at `:858`). It ports to Fabric
**unchanged**. Everything host-specific lives in `FullInjectAgent`, which must be re-implemented
against Knot seams. This document is the map.

### 0a. The one architectural difference from vanilla: **Knot can still natively mix FUTURE targets**

On bare vanilla the app classloader has **no** Mixin at all, so `FullInjectAgent` converts **every**
target and its CFT returns `target'` for both already-loaded and future targets
(`FullInjectAgent.java:159-192`). On Fabric, Knot's `MixinServiceKnot` is a fully-capable live Mixin
pipeline. Once we `Mixins.addConfiguration("liquidbounce.mixins.json")`, Knot **natively applies LB's
mixins to any target that loads AFTER attach** — schema changes included — exactly as the spike proved
(`FABRIC-SPIKE-RESULTS.md:26-28`: a late `addConfiguration` fired on `Minecraft.getWindow`, which
loaded after). Therefore the recommended Fabric model is **hybrid**:

- **Already-loaded targets** (defined before attach) → **converter + retransform** (this design).
- **Future targets** (load after attach) → **let Knot mix them natively**; our CFT returns `null` for
  them. No conversion, no sidecar.

This is *less* work than vanilla, not more: Knot supplies the Mixin service, the transformer, AW
widening for future classes, and synthetic generation. The price is a **mixed world** for LB caller
rewriting (some targets converted, some native) — the one genuinely new complexity (§1f, Risk #2).

---

## 1. Coupling map — every `FullInjectAgent` host seam → Fabric (Knot) equivalent

`FullInjectAgent` pins to the **system classloader** (`SYS = ClassLoader.getSystemClassLoader()`,
`FullInjectAgent.java:19`) and to its **own standalone Sponge service** (`vspike.VSpikeService`,
`:71-80`). On Fabric neither holds: MC lives on KnotClassLoader and the Mixin service is Knot's.

### 1a. Which classloader holds `net.minecraft.*`, and how to reach it

**Vanilla:** system loader; `SYS` used for byte reads (`:51,:109`), `Class.forName(…,SYS)`
(`:88,:199,:219,:225`), and `defineClass` into `SYS` (`:273`).

**Fabric:** **KnotClassLoader** (`net.fabricmc.loader.impl.launch.knot.KnotClassLoader`, `[javap]`
`extends AbstractSecureClassLoader implements KnotClassDelegate$ClassLoaderAccess`). Obtain it at
agentmain via the launcher, exactly as the on-load agents do:
`FabricLauncherBase.getLauncher().getTargetClassLoader()` (`SCHook.java:30,58` uses
`getTargetClassLoader()`; `LBHook.java:11`). This is the loader that already loaded MC and against
which we resolve, define, and read. The agent itself (loaded on the system/app loader via the
`-javaagent`/attach) reaches these classes because **fabric-loader, Sponge Mixin, and ASM are all on
the process's real classpath** (Knot is bootstrapped *from* the system classpath), so
`net.fabricmc.loader.impl.*`, `org.spongepowered.asm.*`, and `org.objectweb.asm.*` resolve to the
**same class identity** Knot uses — the on-load hooks (`SCHook`, `LBHook`) already rely on this
(`SCHook.java:1-14`). Set a `lbrt.Platform.LOADER = knotCL` holder for the reused runtime classes
(§3).

### 1b. Reading an already-loaded MC class's ORIGINAL bytes `O`

**Vanilla:** `SYS.getResourceAsStream(internal+".class")` (`:51,:109`); on bare vanilla the jar bytes
*are* the pristine baseline, so a jar read == `O`.

**Fabric — read through Knot, which exposes exactly the right byte planes** (`[javap]`
`KnotClassDelegate` / `KnotClassLoaderInterface`):

| Byte plane | Knot API (public) | Content | Use |
|--|--|--|--|
| **raw** | `KnotClassLoaderInterface.getRawClassBytes(name)` (also `FabricLauncher.getClassByteArray(name,false)`) | code-source bytes, **pre-ClassTweaker, pre-mixin** | `O` = schema baseline for the diff/`restoreOriginalSchema` |
| **pre-mixin** | `KnotClassLoaderInterface.getPreMixinClassBytes(name)` | raw + **ClassTweaker (AW) applied**, pre-mixin | input to hand the transformer (§1c) |
| post-mixin | `KnotClassDelegate.getPostMixinClassByteArray(...)` (private) | full Knot output | not needed — we drive the transformer ourselves |

`KnotClassLoader implements KnotClassDelegate$ClassLoaderAccess` and the delegate is generic over the
loader, so both public methods are reachable on the live loader (cast to `KnotClassLoaderInterface`,
or call via `FabricLauncher`). MC on Fabric 26.2 is **Mojmap/official at runtime** (no intermediary;
`agent-injection-productionization-plan.md:125-134`, runtime-tested), so dev names == prod names and
LB's mixins/refmap match — the loader-agnostic converter's emitted names line up with the runtime
class exactly, identically to vanilla.

Wire `RetransformConverter.CLASS_BYTES` (`:858`, used only for hierarchy metadata —
`superOf`/`commonSuper`/`nonPublicField`, `:882-923`) to `getRawClassBytes` (fall back to `null` like
vanilla for JDK classes not on Knot). The authoritative `O` for the actual diff of an **already-loaded**
target is the retransform buffer `buf` handed to the CFT (vanilla already rebases onto it via
`RetransformConverter.rebase`, `:169,:852`).

### 1c. Obtaining `X` (Mixin-transformed bytes) for an already-loaded target

**Vanilla:** stands up `vspike.VSpikeService`, `MixinBootstrap.init()` (`:74`), registers configs
(`:75`), and calls `tr.transformClassBytes(name,name,O)` on **its own** transformer (`:80,:110`).

**Fabric — DEFER, reuse Knot's LIVE transformer; do not stand up a second service.** Mixin's
`MixinService.getService()` is a process-global singleton already equal to `MixinServiceKnot`
(`FABRIC-SPIKE-RESULTS.md:28`). `[javap]` `MixinServiceKnot` holds `static IMixinTransformer transformer`
and exposes `static IMixinTransformer getTransformer()`. So:

1. `Mixins.addConfiguration("liquidbounce.mixins.json")` + `"liquidbounce-fabric.mixins.json"` into the
   live service (identical to `SCHook.java:70-71`). This both (a) makes Knot natively mix **future**
   targets (§0a) and (b) makes LB's mixins known to the transformer for our on-demand calls.
2. Acquire the live transformer: `MixinServiceKnot.getTransformer()` (reflective — the method is
   package-private per `[javap]`, or read the `transformer` static field). This replaces vanilla
   `((VSpikeService)MixinService.getService()).createTransformer()` (`:80`).
3. For each **already-loaded** target: `X = tr.transformClassBytes(dot, dot, getPreMixinClassBytes(name))`
   — same call shape as `FullInjectAgent.java:110`, but the input is Knot's **pre-mixin (AW-applied)**
   plane so the transformer's `ClassInfo` metadata (built by `MixinServiceKnot`'s own bytecode provider,
   which also reads pre-mixin) stays consistent — this is the Fabric analogue of vanilla's
   `VSpikeBytecodeProvider` applying the AW so `ClassInfo.findMethod` doesn't return null →
   `LVTGeneratorError` (`VSpikeBytecodeProvider.java:9-15`). `O` for the schema baseline is still the
   **raw** plane (`getRawClassBytes`), so ClassTweaker modifier changes are reset by
   `restoreOriginalSchema` (`RetransformConverter.java:152-211`) and never misread as "added" members
   (the added-member diff keys on name+desc, `:66-76`).

**This is a genuine unknown (Risk #1).** The spike only ever let Knot apply mixins at class *define*
time; invoking Knot's transformer on-demand for an *already-loaded, post-`DEFAULT`-phase* class is not
something the spike exercised. Mixin's lazy config selection may not (re)prepare LB's late-added config
for a target it never organically loaded. Milestone M1 (§5) de-risks exactly this. Mitigant: the
targets that matter most for bootstrap (Minecraft, Gui, Screen, Window) are *always* already loaded, so
M1 can test the exact class whose hooks the client needs.

### 1d. Defining `$$LBSidecar`/`$State` into KnotClassLoader

**Vanilla:** reflective 5-arg `ClassLoader.defineClass(name,bytes,0,len,pd)` into `SYS` with the
**target's exact ProtectionDomain** (`FullInjectAgent.java:50,:273`; eager path `:139-140` uses
`loadedMap.get(internal).getProtectionDomain()`), placing the sidecar in the target's **own package**
so relocated handlers keep package-private access (`RetransformConverter.java:40-43`).

**Fabric — a clean PUBLIC API exists.** `[javap]` `KnotClassLoader` (and `KnotCompatibilityClassLoader`)
expose **`public Class<?> defineClassFwd(String name, byte[] b, int off, int len, CodeSource cs)`** via
`KnotClassDelegate$ClassLoaderAccess`. So sidecar definition is a direct call on the live loader — **no
reflective `defineClass`, no `java.lang` module open needed for this step**:
`((KnotClassDelegate.ClassLoaderAccess) knotCL).defineClassFwd(sidecarName, bytes, 0, len, codeSource)`.
`addToClassPath` is **not** usable here (it registers path/jar code sources, not in-memory generated
bytes — `SCHook.java:55-56` uses it only for the LB jar) — `defineClassFwd` is the correct primitive.

Notes:
- **Package placement can stay vanilla-identical** — sidecars go in the **target's own package**
  (`RetransformConverter.java:42`, `sidecar = targetInternal + "$$LBSidecar"`). Fabric has **no JPMS
  named-module obstacle** (the whole NeoForge §1d headache): Knot is a flat classloader, `net.minecraft`
  is not a sealed module here, so defining a `net/minecraft/.../Foo$$LBSidecar` into KnotClassLoader
  keeps it in the same runtime package as the target → package-private access works, and the converter's
  package-private **fast path** (only genuinely-`private` members reflect, `RetransformConverter.java:40-43,
  :459-467`) is **preserved**. This is a major simplification over NeoForge and matches vanilla exactly.
- **CodeSource / signing:** derive `cs` from the target's `Class.getProtectionDomain().getCodeSource()`
  (already-loaded) or the CFT-supplied PD (future). Fabric's remapped/Mojmap MC jar is **unsigned**, so
  no signer constraint (simpler than vanilla's signed-package worry).
- **Synthetics** (`org.spongepowered.asm.synthetic.*`, `$Anonymous$`): vanilla generates them itself via
  `tr.generateClass` + reflective define (`FullInjectAgent.java:279-303`). On Fabric, KnotClassLoader
  **auto-generates** synthetics on demand when a defined sidecar first references one (Knot's delegate
  routes unresolved names to the transformer's `generateClass`). So the vanilla synthetic dance largely
  **disappears**; keep `tr.generateClass(env,name)` + `defineClassFwd` as a fallback if a synthetic is
  not auto-served.

### 1e. ASM — reference the classpath's ASM, do NOT bundle

The converter uses `org.objectweb.asm.*` heavily (`RetransformConverter.java:1-3`). Fabric already has
ASM on the process classpath (a Knot launch lib). The agent's converter classes (system loader) resolve
ASM against that same classpath → single ASM identity shared with Knot. **Do NOT bundle ASM in the
agent jar**: Fabric aborts on duplicate ASM (`LoaderUtil.verifyClasspath`) and duplicate ASM would split
class identity — the exact fix the spike documents (`FABRIC-SPIKE-RESULTS.md:35-37`). Vanilla's
`verifyAsmRuntime` ASM-version guard (`FullInjectAgent.java:387-395`) is still worth keeping to fail
loud if the wrong ASM wins.

### 1f. AccessWidener vs the converter's AW handling

**Vanilla:** parses LB's AW into `vspike.AccessWidener` (`FullInjectAgent.java:72`) and (a) applies it
on-load to **future** MC classes in the CFT (`awApply`, `:190,:386`), and (b) for **already-loaded**
classes it cannot widen (retransform bans modifier changes) → computes `INACC` (already-loaded +
still-non-public AW targets, `:84-90`) and routes LB's access to those members through **`AwReflect`**
(`Resolver` at `:151-155`, `RetransformConverter.rewriteLbAw` at `:740`).

**Fabric — the future-class widening is Knot's job; already-loaded is `AwReflect`, identical to vanilla:**

| Class state at attach | Vanilla | Fabric |
|--|--|--|
| **future-loaded** MC class | AW applied on-load in CFT (`awApply`) | **Knot's ClassTweaker applies it** on load — register once via `ClassTweakerReader.create(FabricLoaderImpl.INSTANCE.getClassTweaker()).read(aw,"official")` (`SCHook.java:66`). **Our CFT does NOT apply AW** — Knot owns it. |
| **already-loaded** MC class | cannot widen → `AwReflect` | cannot widen post-load (**same JVMTI constraint**) → `AwReflect` — **identical** |

So on Fabric the CFT is **simpler than vanilla**: drop the `net/minecraft/`+`com/mojang/` `awApply`
branch (`FullInjectAgent.java:190`) entirely — Knot widens future classes. `AwReflect.java` drops in
unchanged (reflection with `setAccessible` ignores AW/modifiers, `AwReflect.java:22-24`) except the
`SYS→Knot` loader change (§3). What still needs computing is the `INACC`/`Resolver` predicate for
**already-loaded non-public** members: keep parsing LB's `liquidbounce.accesswidener` with
`vspike.AccessWidener` **as a data structure only** (its `classAccessible`/`touchedClasses` sets,
`AccessWidener.java:8-15,51`) to know which classes/members the AW *would* have widened, intersect with
already-loaded + still-non-public (as vanilla does at `:86-90,:410-419`), and drive the same `Resolver`
(`RetransformConverter.java:728-732`). We never call `AccessWidener.apply` on Fabric — that's Knot's
ClassTweaker.

### 1g. The mixed-world caller rewrite (the new complexity)

Because only the **already-loaded** target subset is converted (§0a), LB's own classes face a mixed
world: dropped interfaces/relocated members exist only for converted targets; future targets keep their
real Knot-applied @Unique members/interfaces. The global tables `GADDED/GFIELD/GIFACE`
(`RetransformConverter.java:378-400`) and `ifaceMap` must be built **only from the converted
(already-loaded) set**. Then the on-load LB caller rewrite (`rewriteCaller`+`rewriteLbAw`+
`AccessorBridgeRewriter`, driven in the CFT `net/ccbluex/` branch, `FullInjectAgent.java:177-183`)
naturally rewrites **only** references to converted-target members (present in the tables), leaving
references to future/native-target members direct — which is correct, because Knot really added those.

**All LB classes are future-loaded on Fabric** (LB is not a mod; it is `addToClassPath`-ed at attach,
so nothing under `net/ccbluex/` was loaded before attach). This is a simplification over vanilla: LB
caller rewriting is **purely on-load via the CFT** — no LB-class *retransform* pass is needed
(vanilla's `preBootstrapLb` retransform at `FullInjectAgent.java:261-264` disappears). The vanilla
preflight of LB caller classes (`preflightLbClasses`, `:396-409`) can still run against the bundled LB
jar to fail loud before bootstrap.

**Risk (§5 Risk #2):** a duck interface implemented by BOTH a converted (loaded) and a native (future)
target, or LB code that expects a dropped interface on receivers of both kinds, can produce
`IncompatibleClassChangeError`/verify errors, because `DuckDispatch` only knows the converted impls
(`DuckDispatch.register`, `FullInjectAgent.java:146-147`). Most LB mixin duck-interfaces target a single
class, so the class is uniformly either loaded (→ dropped → DuckDispatch) or not (→ native → direct).
Mitigation if a partially-loaded interface appears: **force-convert the entire implementor set** of any
interface where any implementor is already loaded (retransform-load the others is impossible, so instead
suppress Knot's native mixin for those specific future targets by returning `target'` from the CFT for
them too — i.e. treat that interface's whole implementor set as "converted").

---

## 2. Bootstrap / init on Fabric, and what late attach must manually kick

**Normal Fabric init:** `fabric.mod.json` declares an **empty** `"main"` entrypoint list
(`src/fabric/resources/fabric.mod.json:20-22`) — LB does **not** boot from a `ClientModInitializer`.
Instead LB boots from a mixin: `MixinMinecraft.startClient` `@Inject`s into `Minecraft.<init>` (at the
`resizeGui()` call) and fires `EventManager.INSTANCE.callEvent(ClientStartEvent.INSTANCE)`
(`src/main/java/.../MixinMinecraft.java:131-133`). `LiquidBounce`'s `startHandler` (a
`handler<ClientStartEvent>`, `LiquidBounce.kt:459`) then runs `initializeClient` → managers/modules/MCEF
(`LiquidBounce.kt:196-238`, sets `isInitialized=true` at `:238`).

**Late attach must manually kick, exactly like vanilla.** `Minecraft` is already fully constructed at
attach, so the `<init>` inject point is in the past and `ClientStartEvent` was never fired (and, even
if we retransform-install the inject, it won't re-run on an already-constructed instance). So reuse
vanilla's kick verbatim (`FullInjectAgent.java:218-247`): on the MC main thread via
`Minecraft.execute(kick)`, `Class.forName("net.ccbluex.liquidbounce.LiquidBounce", true, knotCL)` then
`EventManager.INSTANCE.callEvent(ClientStartEvent.INSTANCE)`. The event system is loader-agnostic Kotlin
— the only change is resolving these classes against **KnotClassLoader** instead of `SYS`.

**Readiness-gated activation (the anti-crash discipline).** Vanilla bootstraps LB to
`LiquidBounce.isInitialized()==true` **before** it retransforms/activates any already-loaded target
(`FullInjectAgent.java:216-269`; `restoreRegistriesAfterInitialization` waits on `isInitialized`
`:347-360`, then `activateLoadedTargets` `:251-270`). This prevents render/entity hooks firing before
Kotlin singletons finish and circularly initializing them. The Fabric late agent **must keep this
ordering** — it attaches into an already-rendering/ticking client, so the window is just as tight.

**JoinGate / registry-thaw — applicable, unchanged (Mojmap names hold on Fabric).** If already in a
world, LB init touches frozen registries; vanilla thaws them transactionally around the kick
(`RegistryThawSession`, `FullInjectAgent.java:305-345`, reflecting `net.minecraft.core.MappedRegistry.frozen`
and `BuiltInRegistries.WRITABLE_REGISTRY`) and defers any in-flight server-join via `JoinGate` /
`JoinGateRewriter` on `net/minecraft/client/gui/screens/ConnectScreen.startConnecting`
(`JoinGateRewriter.java:6-7`). Fabric 26.2 uses the **same** Mojmap classes and field names, so both
components port **unchanged** — with two loader adjustments: (a) `RegistryThawSession`/`JoinGate`
`Class.forName` must target KnotClassLoader (§3), and (b) `JoinGateRewriter.rewrite` output is published
through the Fabric CFT/retransform path (the `ConnectScreen` branch of the CFT,
`FullInjectAgent.java:184-189`), noting `ConnectScreen` may be a future load at menu time → it is
retransform-legal (schema-neutral prologue only) and can also be applied on-load by the CFT.

---

## 3. Reusable (drop-in) vs new (Fabric Platform)

### Drop in UNCHANGED (loader-agnostic)
- **`RetransformConverter.java`** — pure ASM; only needs `CLASS_BYTES` pointed at Knot's
  `getRawClassBytes` (`:858`). All schema-split/sidecar/reflection-lowering logic, **including the
  record constant-pool fix** (`write(...)` seeding the `ClassWriter` from the original CP,
  `:859-878`, the BUG22 fix for `GuiMessage`'s annotated record component), is host-independent and
  ports as-is.
- **`JoinGateRewriter.java`**, **`LateAttachVerifier.java`**, **`AccessorBridgeRewriter.java`** — pure
  ASM, no loader refs. Unchanged.
- **`RetransformConverter.Resolver`** interface and the whole convert-verify-publish transactional gate
  (phase A/B, `FullInjectAgent.java:104-145`) — the *algorithm* is reusable; only its byte-source and
  define/retransform primitives are Fabric-swapped.

### Drop in with a ONE-LINE loader change (`SYS` → Knot)
These three runtime helpers hardcode `ClassLoader.getSystemClassLoader()`; on Fabric the classes they
resolve live on KnotClassLoader:
- **`AwReflect.java:12`** (`private static final ClassLoader SYS = getSystemClassLoader()`).
- **`DuckDispatch.java:10`** (same).
- **`JoinGate.java:8`** (same; used in `open()`/replay `Class.forName`, `:39-41`).

Cleanest fix: introduce a tiny `lbrt.Platform { static ClassLoader LOADER; }` set once at bootstrap to
`getTargetClassLoader()`, and change those three `SYS` references to `Platform.LOADER`. (The
agent-internal `RegistryThawSession` reflection, `FullInjectAgent.java:311-318`, likewise resolves
against Knot — but that code is part of the new Fabric host, so it's authored against Knot directly.)

### NEW — a `FabricPlatform` host (replaces `FullInjectAgent`'s vanilla plumbing)
Most of it is **lift-from-`SCHook`/`LBHook`**, not net-new:
1. **Classloader access** — `FabricLauncherBase.getLauncher()`, `getTargetClassLoader()` (KnotClassLoader),
   `getRawClassBytes`/`getPreMixinClassBytes` byte planes (§1a/1b). Lift the launcher acquisition from
   `SCHook.java:30`.
2. **Payload staging** — extract bundled `agent-libs/*.jar` to temp, `launcher.addToClassPath(lbJar,
   "net.ccbluex")` + deps (`SCHook.java:40-58`). **Do NOT bundle ASM** (§1e). fabric-api must be a real
   installed mod; other deps bundled (`fabric-agent-selfcontained/README.md:18-20`).
3. **Mixin acquisition** — `Mixins.addConfiguration(...)` into the live `MixinServiceKnot`
   (`SCHook.java:70-71`) + `MixinServiceKnot.getTransformer()` for the on-demand `X` (§1c, **new**,
   Risk #1). No `MixinBootstrap.init`, no `VSpikeService`, no `VSpikeBytecodeProvider`,
   no `MixinEnvironment.gotoPhase` — Knot owns all of it (vanilla `:71-82` disappears).
4. **Class definition** — `((KnotClassDelegate.ClassLoaderAccess) knotCL).defineClassFwd(name,bytes,0,
   len,cs)` for sidecar/state (§1d). Replaces `FullInjectAgent.define`/`defineSynthetics`
   (`:273-303`); synthetic generation is mostly free via Knot.
5. **AW application** — `ClassTweakerReader.create(FabricLoaderImpl.INSTANCE.getClassTweaker()).read(aw,
   "official")` for future classes (`SCHook.java:66`) + `vspike.AccessWidener` parsed **as data** for the
   already-loaded `INACC`/`Resolver` (§1f). No CFT `awApply` branch.
6. **Module opens** — `Instrumentation.redefineModule` to open `java.lang` to the agent module for the
   `RegistryThawSession`/reflection paths (vanilla already does this, `:49`). Fabric-loader's
   `net.fabricmc.loader.impl.*` are plain classpath (unnamed module) → no opens needed for
   `MixinServiceKnot.getTransformer`/`FabricLoaderImpl`/`ClassTweakerReader` reflection.
7. **Init kick + readiness gate + registry thaw + JoinGate** — reuse vanilla's orchestration
   (`FullInjectAgent.java:216-270,305-385`) against Knot (§2).

### Payload (build) — reuse the self-contained Fabric agent jar recipe
`fabricSelfContainedAgentJar` (`fabric-agent-selfcontained/README.md:10-16`) already bundles LB classes
+ the LB-owned dep tree by maven group + `liquidbounce.accesswidener`, excluding MC/loader/fabric-api/ASM.
The late-attach agent reuses that layout; the manifest gains `Agent-Class` + `Can-Retransform-Classes`
+ `Can-Redefine-Classes` (the on-load agent only needed `Premain-Class`).

---

## 4. Attach mechanics + Fabric-specific obstacles

### Dynamic attach into a running Fabric JVM
- Entry is **`agentmain(String,Instrumentation)`** (vanilla `FullInjectAgent.agentmain`, `:44`), loaded
  via `VirtualMachine.attach(pid).loadAgent(jar)` from a **separate** injector process. The Injector is
  the same as vanilla — no Fabric-specific change.
- Self-attach needs `-Djdk.attach.allowAttachSelf=true` (disabled since JDK 9,
  `agent-injection-productionization-plan.md:313-316`); external attach avoids that flag.
- `Instrumentation.retransformClasses` + `addTransformer(t,true)` fire for KnotClassLoader-defined
  classes just like any loader (JVMTI operates below the classloader), so the vanilla CFT+retransform
  machinery (`FullInjectAgent.java:159-192,251-270`) has a working substrate.

### Fabric-specific obstacles (rank-ordered) — all mild
1. **Class identity for Mixin/ASM across agent(system) ↔ Knot.** The agent must reference the *same*
   `IMixinTransformer`/`Mixins`/ASM classes Knot uses, and sidecars defined via `defineClassFwd` must be
   visible to `target'` on Knot. Both hold because Mixin+ASM are on the shared system classpath (Knot
   delegates library classes to its parent) and `defineClassFwd` defines *into* KnotClassLoader itself
   (same loader as the targets). Must be smoke-tested (M0) but is the standard Fabric layout the on-load
   agents already depend on.
2. **On-demand transformer selection** (§1c, Risk #1) — Mixin may not re-select LB's late-added config
   for a class it never organically loaded. Mild because the mechanism is the same one that makes the
   spike's future auto-loads work.
3. **Module opens** — only `java.lang` needs opening (vanilla already does it, `:49`); fabric-loader
   impl is unnamed-module classpath, freely reflectable. Trivial.
4. **`getPreMixinClassBytes` for an already-loaded class** re-reads from the code source and re-applies
   ClassTweakers — independent of whether the class is defined, so it works at attach. Verify it doesn't
   throw for classes whose code source was closed.

There is **no JPMS named-module wall** (unlike NeoForge) and **no standalone-service standup** (unlike
vanilla) — Fabric is the *middle* difficulty: Knot gives us the Mixin service, transformer, AW engine,
and a public class-define API for free.

---

## 5. Feasibility verdict, risks, and phased build plan

### Verdict
**Feasible, and the *easiest* of the three loaders' late-attach.** The load-bearing asset — the
loader-agnostic converter core proven at scale on vanilla (146/146 targets clean, per the branch commit
log) — ports unchanged, and every Fabric seam has a concrete, `[javap]`-confirmed mapping that mostly
**reuses the already-proven on-load `SCHook`/`LBHook` calls** (`addToClassPath`, ClassTweaker read,
`Mixins.addConfiguration`) plus **public** Knot APIs (`getRawClassBytes`/`getPreMixinClassBytes`,
`MixinServiceKnot.getTransformer`, `defineClassFwd`). Fabric avoids both hard problems of the other
loaders: **no JPMS module-ownership fight** (flat Knot loader → sidecars stay in the target package,
package-private fast path preserved) and **no standalone Mixin service** (Knot's is live). The three
genuinely new elements are (a) driving Knot's live `IMixinTransformer` on-demand for already-loaded
classes, (b) the hybrid **mixed-world** caller rewrite (converted-loaded vs native-future targets), and
(c) preserving readiness-gated activation against a live render loop. None is a known wall.

### Top 3 risks (ranked)

1. **[HIGHEST] Driving Knot's live `IMixinTransformer` on-demand at agentmain.** We borrow
   `MixinServiceKnot.getTransformer()` post-`DEFAULT`-phase and call `transformClassBytes` on
   already-loaded classes with a **late-added** config. Mixin's lazy per-config selection may already be
   closed, or may not prepare LB's config for a target Knot never organically loaded → `X == preMixin`
   (no LB behavior) for the already-loaded set, silently. The spike proves late configs apply on organic
   *loads*; a direct transformer call for a *loaded* class is the unproven step. If it fails with no
   clean workaround, already-loaded targets get no hooks (menu-time Minecraft/Gui/Screen hooks are
   exactly the loaded set that matters) — that would cap the feature at "attach before those classes
   load," which on Fabric is impossible at the menu. **M1 gates this.**

2. **[HIGH] Mixed-world caller rewrite / duck-interface split.** Only already-loaded targets are
   converted (interfaces dropped → `DuckDispatch`); future targets keep real Knot-applied interfaces.
   An interface implemented by both kinds, or LB code expecting a dropped interface on mixed receivers,
   risks `IncompatibleClassChangeError`/verify failures, since `DuckDispatch` only knows converted impls
   (`FullInjectAgent.java:146-147`). Vanilla sidesteps this by converting **uniformly**; Fabric's hybrid
   reintroduces it. Mitigation: force-convert the whole implementor set of any partially-loaded
   interface (treat those future targets as converted, CFT returns `target'`), at the cost of more
   converter surface.

3. **[HIGH] Init-timing / in-world circular-init + render race under a live world.** Late attach fires
   `ClientStartEvent` and activates hooks into an *already-rendering/ticking* client, tightening the
   window vanilla's readiness gate protects (`FullInjectAgent.java:347-371`). Kotlin `object` singletons
   (module managers) must fully initialize before the first retransformed hook fires. Vanilla's
   ordering (init → then publish target retransforms) must be honored exactly; unverified under Fabric
   late attach, and the tightest gate is attach-while-in-world (M4).

Lesser risks: agent↔Knot class identity for Mixin/ASM (§4.1, smoke-test M0); field-adding mixins on
already-loaded targets legitimately un-retransformable (same soft-fail as vanilla `ChatComponent`,
`LateAttachVerifier.java:56-62`); self-attach JVM-flag / anticheat-shape / ccbluex ToS
(`agent-injection-productionization-plan.md:313-318`); `@Local`/named-LVT mixin fragility against a
non-dev client jar (`agent-injection-productionization-plan.md:326-328`).

### Phased build plan (smallest provable milestone → full)

- **M0 — Attach + payload + config, no conversion.** `agentmain` attaches to a running Fabric client;
  acquire KnotClassLoader; extract+`addToClassPath` the LB payload (no ASM); `ClassTweaker.read(aw)`;
  `Mixins.addConfiguration(...)`; define one trivial generated class via `defineClassFwd` and resolve it
  from a KnotClassLoader-loaded MC class. *Proves class identity + the public define API + payload
  staging (Risk-lesser #1), reusing `SCHook` calls verbatim, no converter.*
- **M1 — One already-loaded target, converted + retransformed.** `MixinServiceKnot.getTransformer()`;
  pick ONE simple already-loaded schema-changing target (e.g. `Minecraft` or a small accessor target);
  `O=getRawClassBytes`, `preMixin=getPreMixinClassBytes`, `X=tr.transformClassBytes(...)`; run
  `RetransformConverter`; `defineClassFwd` the sidecar; `retransformClasses`; verify with
  `LateAttachVerifier`. Confirm `X != preMixin` (LB mixin actually applied). *De-risks Risk #1 — the
  single most important gate.*
- **M2 — Full converter pass at the main menu.** All already-loaded targets from `lb-mixin-targets.txt`,
  transactional convert-verify-publish gate (port `FullInjectAgent` phase A/B), `AwReflect` for
  already-loaded non-public MC access via the `Resolver`, ClassTweaker for future classes, hybrid CFT
  (converted → `target'` on retransform / `null` for future targets Knot mixes). Prove: no schema/verify
  failures, client healthy at menu. *Mirrors the vanilla "146/146 clean" milestone; surfaces Risk #2's
  mixed-world edges.*
- **M3 — LB bootstrap at the menu.** Manual `ClientStartEvent` kick on the MC thread + readiness gate
  before publishing target retransforms; JoinGate installed. Prove: `Launching LiquidBounce`,
  `isInitialized()==true`, MCEF menu, a module (Fly) togglable — the late-attach analogue of the spike's
  menu result (`FABRIC-SPIKE-RESULTS.md:57-63`).
- **M4 — Attach while already in a world.** `RegistryThawSession` around init; activate hooks under a
  live render/tick loop with the readiness gate; resolve any circular-init/render race (Risk #3). Prove:
  Fly works in-world, no NPE/render crash — the hardest gate.

**Where to stop honestly:** if M1 shows Knot's live transformer refuses on-demand `transformClassBytes`
for already-loaded classes (Risk #1) with no non-COEXIST workaround, the honest fallback is
**"Fabric late attach only mixes targets that load *after* attach"** — i.e. attach at the very start of
the menu still hooks most gameplay classes (loaded on world entry), but classes already loaded at attach
get no LB behavior. Even then, the menu-time bootstrap + future-target native mixing covers the common
"attach at menu, then join a server" flow, matching the vanilla feature bar for that case.
