# Agent-injection productionization plan (supersedes the two earlier plans)

Branch: `feat/vanilla-agent`. **Consolidated forward plan. Grounded in what we PROVED, not
assumptions.** Supersedes `three-target-multiloader-plan.md` (framed agent-on-loader as a
separate/harder product) and `agent-on-all-loaders-plan.md` (predicted NeoForge was a
non-starter) — both predictions are now falsified.

## What is actually PROVEN (clean-room reproduced, not assumed)

One idea — **load LiquidBounce into a live MC via a premain Java agent, not as a
mod/ModFile** — now works **in-world on all three environments**:

| Env | LB loaded by agent | Mixins apply | AccessWidener | Module works in-world | UI | Clean-room script |
|--|--|--|--|--|--|--|
| **vanilla** MC 26.2 | standalone Mixin + transforming CL | ✓ (0 fallbacks) | in-agent, transformer + bytecode-provider | ✓ Fly +51 blocks | MCEF | `docs/vanilla-agent-spike/run.sh` |
| **Fabric** (live install) | DEFER into `MixinServiceKnot` | ✓ | ClassTweaker (`getClassTweaker()`) | ✓ Fly +51 blocks | MCEF | `docs/fabric-agent-spike/fabric-agent-run.sh` |
| **NeoForge** (live install) | DEFER into `FMLMixinService` | ✓ | AT into FML's `AccessTransformerEngine` | ✓ Fly Y 67→100.76 (+33.76) | MCEF | `docs/neoforge-agent-probe/neoforge-agent-run.sh` |

In every case LB is **absent from the loader's mod list** and loaded entirely by the agent.
Evidence: `SPIKE-RESULTS.md` / `FABRIC-SPIKE-RESULTS.md` / `NEOFORGE-PROBE-RESULTS.md` +
screenshots + committed `run.sh` clean-room repros. (Markdown table above won't render in
Discord — it's for the repo.)

**So feasibility is retired.** The forward work is **NOT** re-deriving whether this is
possible, nor three separate *build* targets — it is **productionizing three proven
throwaway `run.sh` launchers into distributable, reproducible artifacts.**

## The mechanism, per loader (concrete lessons — the payload that must survive productionization)

The agent has **one entry point + host detection + three back-ends** that share almost no
*mechanism*, only the *payload* (LB's mixin config list + AW bytes + "which classes are ours").

- **vanilla (503 LOC, `vspike/*`)** — no loader exists, so the agent BUILDS the environment:
  standalone `IMixinService`/`Bootstrap`/`GlobalProps`/`ClassProvider`/`BytecodeProvider` over
  `java.lang.instrument`; a **transforming classloader** (needed to generate
  `org.spongepowered.asm.synthetic.*` from `@ModifyArgs` — a bare `-javaagent` can't); AW applied
  in BOTH the transformer AND the bytecode provider (else `ClassInfo.findMethod` returns null on
  AW-widened privates → `LVTGeneratorError`); `sponge-mixin:0.17.3` (only fork with
  `CompatibilityLevel.JAVA_25`) + unshaded ASM ≥9.7; per-package manifest
  `Implementation-Version` (else Mixin rejects JAVA_25). Must EXCLUDE classpath `fabric-loader`
  + dep `sponge-mixin` (rival Mixin services).
- **Fabric (59 LOC, `lbagent/{LBAgent,LBHook}`)** — DEFER: premain `Instrumentation` rewrites
  `FabricMixinBootstrap.init` (non-mod hook; Knot classes are on the app CL) to, at its return,
  `FabricLauncher.addToClassPath(LB, "net.ccbluex")` + apply LB's AW via **ClassTweaker**
  (`ClassTweakerReader.create(FabricLoaderImpl.INSTANCE.getClassTweaker()).read(aw,"official")`)
  + `Mixins.addConfiguration(liquidbounce{,-fabric}.mixins.json)`. **Do NOT bundle ASM** (Fabric
  aborts on duplicate ASM). No standalone Mixin, no transforming CL — Knot owns them.
- **NeoForge (372 LOC, `nfagent/{NFAgent,LbLoader}`)** — DEFER into FML 11. Three seams, each
  hooked via a premain `ClassFileTransformer` using **JDK 25 `java.lang.classfile`** (zero
  external ASM):
  1. **Classloader** — `LbLoader`, a child-first `URLClassLoader` (LB build dirs + kotlin/
     atomicfu/okhttp/okio, LB-exclusive only), parent = `TransformingClassLoader`, chained to
     FML's original `ResourceMaskingClassLoader` fallback, installed via
     `ModuleClassLoader.setFallbackClassLoader`. NeoForge analogue of Fabric `addToClassPath`.
     Needs reentrancy guards on `loadClass`/`getResource`/**`getResources`** (missing plural
     guard = `StackOverflow`).
  2. **AccessTransformer** — hook `AccessTransformerService.<init>` →
     `engine.loadAT(LB's converted AT, "liquidbounce")` so NeoForge applies it natively (Mixin
     metadata sees widened members).
  3. **Mixin configs** — hook `MixinFacade.finishInitialization` → `addMixinConfigContent` +
     `Mixins.addConfiguration` into the live `FMLMixinService`.
  4. **Init trigger** — LB's `initializeClient` (module registration + MCEF) runs as a client
     reload listener wired by the `@Mod` mod bus; with no ModFile that never fires. Replicate it:
     hook `AddClientReloadListenersEvent.<init>` → `NeoForgePlatform.onAddReloadListeners(this)`,
     so `initializeClient` runs during the initial (blocking) reload — before any render frame.

### Cross-cutting lessons (must be captured in code + docs, not re-learned)

- **`ClassFile` `atEnd`-on-`<init>` is a silent no-op** — it places code *after* the
  constructor's `return` (unreachable). Inject **before** the `ReturnInstruction` instead. This
  bug class silently broke two NeoForge hooks; any future `<init>` injection must use the
  before-return pattern.
- **Classloader identity vs single-loader** — the child-first *fallback loader* worked once the
  LB-exclusive packages (kotlin/kotlinx/atomicfu/okhttp/okio) were owned so LB's dep graph shares
  one kotlin (else `LinkageError` on `kotlin.coroutines.Continuation`). The **single-loader
  alternative** (inject LB as a `JarContentsModule` into `FMLLoader.buildTransformingLoader`'s
  module list so the `TransformingClassLoader` itself loads LB) is cleaner-in-theory but hits
  JPMS split-package resolution for kotlin jars unless merged into one `JarContents`. **Fallback
  loader is the proven, lower-risk path; single-loader is the documented alternative.**
- **DEFER is the only viable loader strategy** — Mixin's process-global singleton service means a
  second standalone service (COEXIST) or replacing the loader's (TAKE OVER) both corrupt state.
  Register into the loader's live service; verified on both loaders.
- **MCEF/`libcef` SIGILL under software GL (llvmpipe)** is environmental (VM caveat), not the
  agent — drive via config/menu, not the in-game ClickGUI, on headless/software-GL hosts.

## The real forward work: productionize the three launchers (this is where the effort is)

Each target is a `run.sh` that hand-stitches a classpath + a Gradle init-script + a premain jar.
Productionizing = turning those into artifacts a user can actually attach, reproducibly, in CI.

### §A — vanilla → real `:vanilla` Gradle subproject (the biggest lift; ~a week, per prior review)
- Promote `docs/vanilla-agent-spike/` into a `:vanilla` subproject (sibling of `:neoforge`),
  reusing `src/main`. Produces a **fat/shadow agent jar** with `Premain-Class`.
- **The hard 80% is `run.sh`→Gradle:** three hand-stitched caches (Mojmap client jar from
  neoformruntime; assets from loom; deps from modules-2). `run.sh` currently reuses the **Fabric**
  compiled output + `liquidbounce-fabric.mixins.json` (greps out fabric-loader/sponge-mixin) — a
  clean subproject with its own source set will NOT reproduce that classpath without re-deriving
  the no-loader companion mixin set.
- Shadow mechanics that fight conventions: `mergeServiceFiles`; explicit `org/objectweb/asm/`
  manifest `Implementation-Version`; delete `module-info.class`; strip `*.SF/*.RSA/*.DSA`; bundle
  `sponge-mixin:0.17.3` while excluding the dep copy of the same coordinate.
- **loom fights the run model** — loom's `runClient` launches through Knot (the thing the agent
  excludes). Keep loom/moddev purely as a **Mojmap-MC + assets provider** with a fully custom
  `JavaExec` run, not `runClient`. Real infra, not a toggle.

### §B — Fabric + NeoForge agent artifacts → proper packaging
- The Fabric/NeoForge agents are premain jars + a Gradle init-script that (i) deregisters LB as a
  dev mod and (ii) attaches `-javaagent`. **Dev-only today** (init-script targets loom/ModDevGradle
  `runClient`). For a real install the agent attaches to the launcher's JVM args — but this is
  **unproven against a production launcher**, and the **mapping namespace is NOT uniform** (see the
  CRITICAL objection below):
  - **vanilla** — MC 26.2 ships **Mojmap/official even in production** (verified: `client_mappings`
    absent, raw jar has `net/minecraft/client/Minecraft.class`, 0 obfuscated classes). Agent loads
    the jar directly → official → **no refmap.**
  - **Fabric** — **RUNTIME-TESTED, no refmap needed** (2026-07-10, `agent-injection-mapping-test/`).
    The reviewer's "production remaps to intermediary" objection is **FALSIFIED**. Proven at four
    levels: (1) raw Mojang 26.2 client jar is Mojmap (0 obfuscated); (2) Fabric publishes **no
    intermediary for 26.2** (`intermediary/26.2` = HTTP 404); (3) `fabric-loader 0.19.3
    MappingConfiguration.computeRuntimeNamespace()` picks `intermediary` **only if the mappings
    contain it**, else `official`; (4) a **production-mode** launch (`fabric.development=null`, no
    loom, direct `KnotClient`) logged `Mappings not present!` + `Fabric runtime namespace = official`
    and loaded `net/minecraft/client/Minecraft` (Mojmap name), not `class_310`. LB's mixins/AW +
    `LBHook`'s `read(aw,"official")` are therefore correct for stock Fabric 26.2. (Fabric's
    intermediary is legacy compatibility for pre-Mojmap versions — irrelevant to 26.2.)
  - **NeoForge** — 26.2 runtime uses Mojmap names (its own official-mappings pipeline; the in-world
    stack traces show `net.minecraft.client.Minecraft`). LB's neoforge build produces **no refmap**.
    Same situation as the other two. (A production-launcher-profile test would confirm, but the
    namespace risk is resolved — 26.2 is Mojmap everywhere.)
- Package each agent as a self-contained jar that carries LB's compiled classes + resources + the
  loader-exclusive deps it injects (kotlin, atomicfu, okhttp, mcef, …), so the user attaches ONE
  `-javaagent:liquidbounce-agent-<loader>.jar` with no external classpath assembly.

### §C — MCEF / Via / DJL bundling, consistent across all three
- **MCEF:** vanilla reused the Fabric `mcef` jar (works, software-rendered). Decide the canonical
  browser artifact per target and bundle consistently. **Unmerged dependency:** the NeoForge target
  needs `mcef-neoforge:3.3.2-26.2-SNAPSHOT` which lives only in `~/.m2` (HTTP 404 on
  maven.ccbluex.net) — CI-red by construction; must be published or CI-prebuilt from
  `obus-globus/mcef@neoforge-26.2`.
- **Via:** functional on Fabric (ViaFabricPlus) only; present-but-inert (compiled, `isModLoaded`
  → false) on NeoForge + vanilla until a ViaForge/agent-Via path exists. State the policy; don't
  imply Via works everywhere.
- **DJL:** compiled-in on all three; mirror the `jij` list into `:vanilla`.

### §D — distribution / packaging: how a user actually attaches the agent
- **The open product question.** Options, cheapest→richest: (1) documented manual
  `-javaagent:...jar` + JVM args added to a launcher profile (MultiMC/Prism/vanilla launcher);
  (2) a small installer that writes the profile; (3) a self-attaching bootstrap (`SelfAttach` /
  `VirtualMachine.attach`) — note NeoForge's own dev launch already uses `DevAgent`/`SelfAttach`,
  a proven pattern to mirror. Decide the target surface before building.
- Host detection: the single entry point must detect vanilla vs Fabric vs NeoForge at premain and
  pick the back-end (presence of `net.fabricmc.loader` / `net.neoforged.fml` on the classpath).

### §E — CI implications
- Three targets = three build+launch jobs. The two loader agents can reuse the loaders' existing
  dev envs; `:vanilla` must reproduce `run.sh`'s cache/asset assembly in CI (no published MC-loader
  dep). Keep `MixinDivergenceCheck` on shape-divergent targets. **NeoForge CI is red until
  `mcef-neoforge` is published/prebuilt — that's the cheapest first unblock, LB-code-free.**
- Adding `:vanilla` to `settings.gradle.kts` mutates the default task graph (inherits
  `mavenLocal()`; unqualified `./gradlew build` fans out to the heavy agent build). Opt-in vs
  aggregate is a P0 decision.

## Milestones (once greenlit — reordered by the adversarial review: risk-first, not build-first)

- **P0 — namespace/refmap risk: RESOLVED (done, not a future milestone).** Runtime-tested: stock
  production Fabric 26.2 runs on `official`/Mojmap names, no intermediary, no refmap (see the
  namespace section + `agent-injection-mapping-test/`). 26.2 is Mojmap on all three loaders. The
  remaining P0-equivalent risk is now purely the **Gradle-umbilical / `-Dlb.*` self-containment**
  (P1 below) + proving against a **real launcher profile** — a packaging test, not a feasibility one.
- **P1 — self-contained agent jar** (§D/§3): read LB's classes/resources/AT/refmap as **jar
  entries**, not `-Dlb.*` filesystem paths (`NFAgent` `FileReader(System.getProperty("lb.at"))`
  and `LBHook`'s `-Dlb.classes*` must become jar-relative reads). Prove all three with a bare
  `java -javaagent:… Main`, zero Gradle.
- **P2 — unified entry point + host detection** (§D): today there are three separate hardcoded
  `Premain-Class` agents; the "one agent, detect vanilla/Fabric/NeoForge, three back-ends" is
  **net-new and unbuilt** — detect by resource probe before touching loader classes, gate the
  vanilla standalone service behind "no loader found."
- **P3 — dependency-identity map** (§C/§5): enumerate `(LB's full dep tree) ∩ (each loader's
  provided classes)` **empirically** (the `LbLoader.OWNED` list is hand-grown and provably
  incomplete); own the intersection child-first or delegate it. Prove the **MCEF native `libcef`**
  load path once per loader on a real GPU (native libs have process-global identity — can't be
  loaded by two classloaders).
- **P4 — `:vanilla` Gradle subproject + fat agent jar** (§A) — real but now *after* the risk is
  retired; the vanilla target is production-safe on namespace, so it's the lowest-risk build-out.
- **P5 — CI (scoped honestly, §E):** build + boot-to-mixin-apply asserted via log markers
  (`registered LB mixin configs`, `loaded LB AccessTransformer`); **functional / MCEF / in-world
  verification goes on a GPU runner or a manual gate — it CANNOT run headless** (software-GL MCEF
  SIGILLs). Publishing `mcef-neoforge` unblocks the *build* job but does not make CI prove function.

## Explicitly NOT in this plan / known gaps
- Fixing the software-GL MCEF crash (environmental) — and note it blocks headless functional CI.
- ViaForge / agent-Via functional protocol translation.
- Whether to migrate NeoForge to the single-loader (game-layer module) architecture vs keep the
  proven fallback loader.

## Adversarial review outcome (verdict: NEEDS-MAJOR-REWORK on productionization; mechanism sound)

A reviewer attacked the plan against the results docs + agent code. Objections folded in above;
the load-bearing ones:

1. **[CRITICAL → RESOLVED] The reviewer's "production Fabric remaps to intermediary → needs refmap"
   was itself an untested assertion — and it is FALSIFIED.** Runtime-tested (2026-07-10): stock
   Fabric 26.2 runs on `official`/Mojmap, no intermediary (`Mappings not present!` +
   `runtime namespace = official`, loading `net/minecraft/client/Minecraft`). The original plan's
   "dev=prod names in 26.2" claim was, for 26.2, correct on all three loaders; the overclaim was
   only that it was stated without proof. Now proven. (scorpion called this; the mapping layer is
   legacy compat for pre-Mojmap versions.)
2. **[HIGH] The proven artifact is "agent + Gradle env-assembler," not "agent jar."** The scripts
   do all classpath/dep/namespace assembly via loom/ModDevGradle; NeoForge even resolves the
   fallback deps from a live `sp.configurations.runtimeClasspath` inside the init script. An end
   user has no Gradle. Severing this umbilical (P1) is bigger than the `:vanilla` subproject.
3. **[HIGH] `-Dlb.*` are dev-artifact paths** the init scripts set; a packaged agent has no one to
   set them → must self-supply from the jar (P1).
4. **[HIGH] Unified host-detecting agent is unbuilt** — three separate agents today (P2).
5. **[MED-HIGH] Bundling is recurring `LinkageError`, not a one-time choice.** `OWNED` is
   hand-grown (ahocorasick/okhttp added reactively after failures); MCEF native `libcef` identity
   is untested across loaders and `mcef-neoforge` is unpublished (P3).
6. **[MED] CI can't prove function headlessly** (P5 scoped accordingly).
7. **[MED] "DEFER is the only viable strategy" / fallback "lower-risk" are stated stronger than
   proven** (DEFER proven for the two loaders; fallback needed three reentrancy guards + surfaced
   the whole LinkageError chain — earned, not inherent).

**Confirmed NOT overclaimed:** the Fly evidence (+51 blocks Fabric, +33.76 NeoForge) checks out
against the results docs with before/after screenshots. **Under-disclosed:** vanilla's "0
fallbacks" required the `MixinLocalPlayer` fix (17 core events); the same `@Local`/named-LVT
fragility is a production risk against any client whose LVT differs from the dev jar.

**Bank point:** this plan is reviewed and grounded. No build until scorpion greenlights — and if he
does, **P0 (real-launcher Fabric spike) first**, because it can invalidate everything downstream.
