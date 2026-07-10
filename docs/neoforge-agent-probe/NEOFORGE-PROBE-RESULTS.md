# NeoForge agent-injection probe

**Question (spike-gated):** can LiquidBounce be loaded on NeoForge by a premain Java agent —
NOT installed as a mod — the way it now is on vanilla and Fabric?

A prior static seam-map (subagent) concluded **NO**: "premain `ClassFileTransformer` can't
hook `Minecraft` — timing mismatch, sealed `ModuleLayer`, FML's classloader not yet created;
mixin configs locked at init." That is a *reasoned* wall from reading bytecode — the classes
it cited exist regardless of whether injection works, so the evidence doesn't actually support
the conclusion. We tested it empirically instead.

---

## Tier 1 — KILL-SHOT: premain agent transforms `Minecraft` on live NeoForge — **GREEN**

**Probe** (`src/nfprobe/NFProbe.java`): a `-javaagent` whose `premain` registers a whole-JVM
`ClassFileTransformer` (`inst.addTransformer(t, true)`). When `net.minecraft.client.Minecraft`
is defined, it injects a `System.out.println` into `<clinit>` and `run` using **JDK 25's
built-in `java.lang.classfile`** (JEP 484, final in JDK 24) — *zero external ASM*, so there is
no duplicate-ASM abort risk (which bit the Fabric agent).

**Substrate:** the real `:neoforge` `runClient` (MC 26.2, NeoForge 26.2.0.7-beta), launched via
`nf-probe.gradle` init script that **clears ModDevGradle's `ModFoldersProvider`** — its sole
job is registering LB as the dev mod `liquidbounce`, so clearing it means **LB is not a
discovered ModFile**. Other mods (sodium/lithium/mcef/immediatelyfast/neoforge) still load
normally via the module path.

**Result** (`nf-probe-airtight-proof.log`):
```
Reloading ResourceManager: vanilla, mod_resources, mod/sodium, mod/lithium,
                           mod/mcef, mod/immediatelyfast, mod/neoforge   ← NO liquidbounce
[NFPROBE] premain fired — java=25.0.3 retransformSupported=true redefineSupported=true
[NFPROBE] transform() SAW net/minecraft/client/Minecraft
          loader=net.neoforged.fml…TransformingClassLoader module=minecraft
[NFPROBE] transformed Minecraft (161997 -> 162397 bytes)
[NFPROBE-INJECTED] executing inside Minecraft.<clinit> … premain agent, NOT a mod
[NFPROBE-INJECTED] executing inside Minecraft.run   … premain agent, NOT a mod
Sound engine started    ← boot healthy post-transform (no VerifyError)
```

**What this establishes / refutes:**
- ✅ A premain JVM agent transforms `Minecraft` on NeoForge's `TransformingClassLoader`,
  with LB not a mod. Arbitrary bytecode injection into the game is possible via agent.
- ❌ Refutes the seam-map's central claim. `addTransformer(t, true)` fires for classes
  defined by **any** classloader — including `TransformingClassLoader` long after premain —
  so there is no "classloader not created yet / timing mismatch" window. The transformer sees
  `net/minecraft/client/main/Main` and `Minecraft` on the FML loader.
- ✅ `retransform`/`redefine` both report `supported=true`.

**Scope — what Tier 1 does NOT prove:** LB drives 150+ **Mixins** through FML's mixin service,
not raw transforms. Tier 1 proves the *hardest thing the analysis said was impossible* (touch
`Minecraft` post-`ModuleLayer`-seal via agent), but the full LB path depends on Mixin. That is
Tier 2.

## Reproduce
```
docs/neoforge-agent-probe/  →  build the agent, then run with the init script:
  javac --release 25 -d out src/nfprobe/NFProbe.java
  jar cfm /tmp/nfprobe.jar <manifest: Premain-Class: nfprobe.NFProbe, Can-Retransform-Classes: true> -C out nfprobe
  ./gradlew :neoforge:runClient --init-script nf-probe.gradle   # DISPLAY set; needs :neoforge built
```

---

## Tier 2 — Mixin-DEFER: agent registers a Mixin into FML's live service — **GREEN**

The real LB mechanism is Mixin, not raw transforms. Tested whether a premain agent can register
a Mixin config into FML's **live** mixin service without a ModFile — the DEFER approach that worked
on Fabric (`Mixins.addConfiguration` into the loader's own service).

**Verified from the loader jar (`javap`), not assumed:**
- `FMLMixinService.addMixinConfigContent(String, byte[])` buffers config bytes in a
  `Map<String,byte[]> mixinConfigContents`; `getResourceAsStream` checks that buffer **first**,
  then falls back to the context classloader → a buffered config needs no classpath entry.
- `MixinFacade.finishInitialization(LoadingModList, TransformingClassLoader)` runs
  `addMixins` → `gotoPhase(INIT)` → `gotoPhase(DEFAULT)` → `MixinBootstrap.init()` →
  `getPlatform().inject()`. Registering a config at the head (before `inject()`) is honored.
- `FMLClassBytecodeProvider.getClassNode` loads mixin **class** bytes via FML's `BytecodeProvider`,
  else `Thread.getContextClassLoader().getResource(name+".class")`.

**Diagnostic (all confirmed live):**
```
[NFREG] MixinFacade.finishInitialization HOOKED by premain agent
[NFREG] MixinService.getService() = net.neoforged.fml.loading.mixin.FMLMixinService
[NFREG] TransformingClassLoader.getResource(nfprobe/NFProbe.class) = jar:file:/tmp/nfprobe.jar!/…
```
The last line is decisive: the transforming loader resolves classes straight from the **agent jar**,
so the mixin class is reachable by FML's bytecode provider — the class-visibility problem that forced
`addToClassPath` on Fabric does not exist here.

**Probe:** the agent hooks `MixinFacade.finishInitialization` (inline bytecode, so it uses FML's own
view of the Mixin classes — avoids the classloader-duplication trap) and calls
`FMLMixinService.addMixinConfigContent(name, bytes)` + `Mixins.addConfiguration(name)`. The trivial
`@Mixin(targets="net.minecraft.client.Minecraft") @Inject(method="run", at=HEAD)` and its config live
in the agent jar. Raw Tier-1 Minecraft transform disabled (`-Dnfprobe.rawMc` unset) so the proof is
purely the mixin.

**Result** (`nf-probe-tier2-mixin-proof.log`), LB deregistered (mod list =
sodium/lithium/mcef/immediatelyfast/neoforge, **no liquidbounce**):
```
[NFREG] registered nfspike.mixins.json into live FMLMixinService + Mixins.addConfiguration — agent, not a mod
[NFMIXIN] >>> agent-registered MIXIN fired inside net.minecraft.client.Minecraft.run
          on a LIVE NeoForge install — registered via FMLMixinService by a premain agent, NOT a mod <<<
Sound engine started   ← boot healthy
```

**A premain agent registered a Mixin into FML's live service, with no ModFile, and it applied to
Minecraft.** This is the exact mechanism LB needs.

---

## VERDICT: **YES** — NeoForge agent-injection is feasible (evidence-backed)

Both the fundamental capability (Tier 1: transform Minecraft) and the real LB mechanism (Tier 2:
register a Mixin into FML's live service without a mod) are proven on a live NeoForge 26.2 install.
The prior static seam-map's "NO" rested on three claims, **all empirically false**:
- "premain transformer can't hook Minecraft (timing / sealed layer)" — it does (Tier 1);
- "mixin configs locked at init, no late window" — `finishInitialization` head injection is honored (Tier 2);
- "mixin class needs a ModFile / classloader-isolated" — the transforming loader resolves the agent jar directly.

**Remaining engineering for full LB-on-NeoForge-via-agent** (mechanism proven, scope is build-out,
mirrors the Fabric interposer):
- register LB's real configs (`liquidbounce.mixins.json` + `liquidbounce-neoforge.mixins.json`) the
  same way, and make LB's classes + kotlin runtime + resources resolvable by the transforming loader
  (they resolve from the agent/launch classpath, as the `getResource` test shows);
- apply LB's **AccessTransformers** (NeoForge uses ATs, converted from LB's AccessWidener by the build's
  `convertAccessWidener`) — the NeoForge analogue of the Fabric ClassTweaker step;
- MixinExtras is already present at runtime (0.5.4); dev namespace is mojmap so no refmap is needed in
  dev (a production install would ship LB's refmap).

---

## Full build-out — LB-on-NeoForge via the agent

Built the full interposer (`lb-agent/src/nfagent/{NFAgent,LbLoader}.java`) + clean-room script
(`neoforge-agent-run.sh`). Three FML-11 seams, all verified by javap and working:
1. **Fallback loader** — `LbLoader` (child-first URLClassLoader over LB's build dirs + kotlin/
   atomicfu/kotlinx deps, parent = TCL, chained to FML's original `ResourceMaskingClassLoader`
   fallback) installed via `ModuleClassLoader.setFallbackClassLoader`. The NeoForge analogue of
   Fabric `addToClassPath(Knot)`: LB classes/resources resolve *and* see `net.minecraft.*`.
   Needed reentrancy guards on `loadClass`/`getResource`/`getResources` to break the
   TCL⇄fallback delegation loop (a missing `getResources` guard caused a `StackOverflowError`).
2. **AccessTransformer** — hook `AccessTransformerService.<init>` → `engine.loadAT(LB's AT,
   "liquidbounce")` so NeoForge applies LB's 166-line AT natively (Mixin metadata sees widened
   members — no LVTGenerator/IllegalAccess errors).
3. **Mixin configs** — hook `MixinFacade.finishInitialization` → register `liquidbounce.mixins.json`
   + `liquidbounce-neoforge.mixins.json` into the live FMLMixinService (the proven DEFER path).

### (b) LB initializes on NeoForge via the agent — **GREEN**
`nf-lb-init-proof.log` / `nf-lb-menu.png`. LB deregistered as a mod (mod list =
sodium/lithium/mcef/immediatelyfast/neoforge — **no liquidbounce**). LB's mixins apply
(e.g. `MixinBuiltInRegistries` into `net.minecraft.core.registries.BuiltInRegistries`),
`Launching LiquidBounce v0.38.1 by CCBlueX`, 36 Render Pipelines loaded, boots to the menu,
window branded `LiquidBounce v0.38.1 (dev)`. Clean, no fatal mixin/AT/classloader errors.

### (c) module functional in-world — **BLOCKED (agent-specific), not yet achieved**
Entering a world with LB-via-agent crashes:
```
NullPointerException: ValueGroup.tree, parameter valueGroup is null
  ModuleKillAura.<clinit>(ModuleKillAura.kt:591)         <- tree(KillAuraAutoBlock) where the object is still null
  KillAuraAutoBlock.<clinit>  (object : ToggleableValueGroup(ModuleKillAura, ...))
  ModuleSwordBlock.shouldHideOffhand(ModuleSwordBlock.kt:78)   <- first touch, during render/tick
```
A circular Kotlin-`object` init: if `KillAuraAutoBlock` initializes before `ModuleKillAura`,
`ModuleKillAura.<clinit>` reads the half-constructed `KillAuraAutoBlock` as null → NPE.

**Positive control (the key test): LB as a NORMAL MOD enters the same worlds cleanly** —
`nf-baseline-inworld-proof.log` (1688 advancements, no NPE), and it shows LB's **MCEF main
menu** (`nf-baseline-mcef-menu.png`) whereas the agent run shows the **vanilla** title screen.
So the crash + the missing custom menu are **agent-specific**, not LB or MC bugs.

**Diagnosis** — LB-on-NeoForge is not yet *fully* equivalent via the agent:
- LB's `@Mod` class `LiquidBounceNeoForge` registers on the **mod event bus**
  (`AddClientReloadListenersEvent`); with no ModFile there is no mod bus, so that init is
  skipped. LB's async (coroutine) module registration then races the first render tick, so
  `ModuleKillAura` isn't fully class-initialized before `ModuleSwordBlock` touches
  `KillAuraAutoBlock` → the circular-init NPE. The vanilla-vs-MCEF menu is the same class of gap.
- Fabric didn't hit this because LB's Fabric init is purely mixin/entrypoint-driven with no
  equivalent mod-bus dependency.

**Remaining work for (c) parity** (identified, not trivial): replicate the `@Mod` mod-event-bus
registration from the agent (or eagerly force-initialize LB's module objects after startup to fix
the init-order race), and get MCEF's custom menu to render via the agent. This is LB-integration
completeness work on top of the (now-proven) load mechanism.

## Reproduce Tier 2
```
docs/neoforge-agent-probe/
  javac --release 25 -d out src/nfprobe/NFProbe.java
  javac --release 25 -cp <mixin-0.8.jar> -d out src/nfspike/MixinNfMc.java
  cp src/nfspike.mixins.json out/ ; jar cfm /tmp/nfprobe.jar <manifest> -C out .
  ./gradlew :neoforge:runClient --init-script nf-probe.gradle   # LB deregistered; watch for [NFMIXIN]
```
