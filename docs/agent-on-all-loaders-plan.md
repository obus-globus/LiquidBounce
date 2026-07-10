> **SUPERSEDED** by `agent-injection-productionization-plan.md` (2026-07-10). Agent-injection is now proven in-world on all three loaders (vanilla/Fabric/NeoForge), which falsifies this doc's framing (three build targets / NeoForge non-starter). Kept for history.

# One agent-injection path for LiquidBounce on vanilla + Fabric + NeoForge — plan

Branch: `feat/vanilla-agent`. **Plan only — spike is the gate, no full build yet.**

## Goal (restated so scorpion can veto in one read)

Make **the agent-injection method itself** load LiquidBounce on all three environments:
bare **vanilla** (done), a **Fabric** install, and a **NeoForge** install — *one
injection path*, not three separate build targets. On a loader install the target MC is
already running under that loader's own Mixin + classloader; the agent must load LB
*into* that live environment.

### Use case — confirm this is actually wanted (it changes everything)

On a Fabric/NeoForge install, LB can simply be **installed as a mod** — the loader then
natively loads its classes, applies its AccessWidener, and registers its mixin configs.
That is the easy, supported path. **Agent-injection only makes sense if the goal is
"attach LB to an *existing* install without installing it as a mod"** — e.g. a portable
injector that hooks any client, keeping LB out of the mod list / not requiring mod
install rights. That is a real but niche (and more adversarial) use case, and it is
*strictly harder* than shipping a mod. **Scorpion should confirm this is the intent**;
if he just wants LB usable on Fabric/NeoForge, the mod path (already working — both
loaders boot in-world, see `neoforge/run/logs/latest.log`) is the answer and this plan
is unnecessary.

Assuming the answer is "yes, attach-without-installing," the rest of this plan applies.

## The crux: coexisting with a live loader Mixin subsystem

SpongePowered Mixin is a **process-global singleton**: `MixinService.getService()`
resolves exactly one `IMixinService` via `ServiceLoader`, and the environment/phase state
is static. On a loader install that service is already chosen and initialised —
**Fabric: `MixinServiceKnot` + `FabricGlobalPropertyService` + `KnotClassLoader`**;
**NeoForge: `MixinServiceModLauncher` under cpw ModLauncher + a JPMS module layer.**

This is precisely what crashed the vanilla premain: with `fabric-loader` on the
classpath, *its* `FabricGlobalPropertyService` got selected over our standalone service
and NPE'd. On vanilla we escaped by **excluding fabric-loader**. **On a real install we
cannot exclude the loader — it is running the game.** So the three options:

- **(a) DEFER** — do *not* stand up our own Mixin. Register LB's mixin configs into the
  loader's already-running Mixin (`Mixins.addConfiguration("liquidbounce.mixins.json")`),
  and let the loader's Mixin + classloader apply them. Hand LB's AccessWidener to the
  loader's AW machinery. **Recommended for both loaders.**
- **(b) COEXIST** — run a second, isolated Mixin instance alongside the loader's.
  **Not viable**: Mixin's singleton service + static environment state make two live
  instances in one classloader-graph conflict; true isolation would need a whole separate
  classloader universe for the game, i.e. re-hosting MC (defeats the point).
- **(c) TAKE OVER** — replace the loader's Mixin service with ours. **Not viable**: the
  loader already initialised and transformed classes through its service; swapping mid-run
  corrupts state and double-transforms.

**Recommendation: DEFER on both Fabric and NeoForge.** You are *using* the loader's Mixin,
not competing with it — which sidesteps the service-selection crash by construction. The
hard parts move to **timing, classloader identity, and AW ordering** (below), not service
conflict.

## Fabric — deferred injection architecture

The agent must do, in Knot's world, the three things fabric-loader does for a mod:

1. **Get LB's classes onto `KnotClassLoader`.** LB isn't a mod, so Knot doesn't know it.
   The agent adds LB's jar to Knot's classpath (`KnotClassLoader`/`KnotClassDelegate` has
   an `addUrl`-style path, reachable reflectively) so that MC classes (Knot-loaded) and
   LB's injected code + mixin classes share one classloader identity. Getting this wrong
   → `NoClassDefFound` / `LinkageError` from split loaders (the same class-identity trap
   we hit on vanilla).
2. **Register LB's mixin configs into the live Mixin at the right phase.**
   `Mixins.addConfiguration(...)` must run **after** `MixinServiceKnot` is initialised but
   **before** the target MC classes are transformed/loaded — i.e. Fabric's PreLaunch
   window. Options: (i) inject a synthetic **PreLaunch entrypoint** (fabric provides
   `PreLaunchEntrypoint`) — but registering an entrypoint basically means being a mod;
   (ii) **Instrumentation-hook `FabricMixinBootstrap`/Knot** to call `addConfiguration`
   right after Fabric adds its own configs. (ii) keeps LB a non-mod but is fragile against
   loader-version changes.
3. **Apply LB's AccessWidener via Fabric's AW machinery, not our own transformer.** On
   vanilla our Instrumentation transformer applied the AW *before* Mixin. On Knot, a raw
   `ClassFileTransformer` runs at `defineClass` — **after** Knot's Mixin transform — which
   is too late for injectors needing widened members. The fix is to feed LB's AW into
   Fabric's `AccessWidener` (`FabricLauncherBase`) so it's applied in the same pass as
   other mods' wideners, before Mixin. (This is also the clean answer to Koda's
   "AW-interacts-with-loader-AW" question: reuse the loader's AW pass; don't run a second.)

**Synthetic classes are FREE here.** Because we defer to Knot's Mixin, Knot's classloader
already knows how to generate/load `org.spongepowered.asm.synthetic.*` (from `@ModifyArgs`
etc.). **The vanilla transforming-classloader trick is NOT needed on a loader** — the
loader owns the classloader and does it. Big simplification vs vanilla.

## NeoForge — deferred injection architecture (harder)

Same DEFER strategy, but the plumbing is cpw **ModLauncher** + a **JPMS module layer**,
which is materially harder than Knot:
- Classes load through ModLauncher's `TransformingClassLoader` inside a module layer;
  adding LB's classes/mixins means inserting into that module graph, not a flat URL list.
- Mixin runs as a ModLauncher **launch plugin** (`MixinServiceModLauncher`); registering
  LB configs at the right transformation phase requires hooking that plugin's lifecycle.
- AW→AT: NeoForge uses AccessTransformers; LB's AW is converted to an AT for the mod
  build (`ConvertAccessWidenerTask`). Agent-injected, the agent must feed that AT into
  NeoForge's AT engine before Mixin — analogous to the Fabric AW step but through a
  different API.
- **Prediction (to verify, not assert):** NeoForge agent-injection is the highest-risk
  target; JPMS + ModLauncher may make non-mod runtime injection impractical, in which
  case the honest answer is "NeoForge requires the mod path." The spike (below) is Fabric;
  a NeoForge spike is a **separate, later** gate.

## What's shared vs per-environment in the agent

- **Shared core:** the LB mixin config list + AccessWidener bytes; the "which classes are
  ours" knowledge; the decision logic.
- **Per-environment strategy (the agent detects its host and switches):**
  - **vanilla** → stand up standalone Mixin service + transforming classloader + in-agent
    AW (done).
  - **Fabric** → DEFER: Knot classpath insert + `addConfiguration` at PreLaunch + AW into
    Fabric's AW pass. No standalone Mixin, no transforming classloader.
  - **NeoForge** → DEFER via ModLauncher/JPMS (highest risk).
- So "one injection path" is really **one entry point with host detection + three
  back-ends**, sharing the config/AW payload. Be honest that the vanilla back-end and the
  loader back-ends share almost no *mechanism* — only the payload.

## Cheapest feasibility spike (the gate — proves/kills the riskiest assumption)

**Riskiest assumption:** the agent can get **one** LB (or trivial) mixin to apply on a
**live Fabric install** without fatally conflicting with fabric-loader's Mixin.

**Spike (Fabric only, ~a day):**
1. Stand up a real Fabric MC 26.2 install (fabric-loader + fabric-api, no LB mod).
2. Attach a minimal agent that, at Fabric's PreLaunch window, calls
   `Mixins.addConfiguration("spike.mixins.json")` for a trivial mixin injecting a
   `println` into `Minecraft.<init>` — **without** calling `MixinBootstrap.init()` or
   registering any `IMixinService` (defer entirely to `MixinServiceKnot`).
3. Ensure the spike mixin class is reachable by `KnotClassLoader`.
4. **Green = the println fires and the game boots** (no `FabricGlobalPropertyService`
   crash, no double-transform). That proves DEFER works and retires the coexistence risk.
   **Red = premain/service conflict or the config never applies** → agent-on-loader is
   likely impractical and we tell scorpion to use the mod path on loaders.

Add one AW sub-check: have the spike mixin touch an AW-widened private member routed
through Fabric's AW pass, to prove the AW-ordering approach.

A **separate** NeoForge spike follows only if the Fabric spike is green and scorpion still
wants NeoForge agent-injection.

## Honest cost / recommendation

- Fabric defer: plausible, ~gated by the spike; the timing-hook + Knot-classpath insert
  are the fiddly bits.
- NeoForge defer: high risk (ModLauncher/JPMS); may prove impractical.
- **If the use case is "just run LB on Fabric/NeoForge," the mod path already works** and
  is far cheaper — recommend that unless "attach without installing" is a hard requirement.

## Adversarial review outcome (verdict: SOUND-WITH-FIXES — mechanism viable on Fabric)

Two reviewers + my own jar checks. **The Fabric defer mechanism is viable** (I initially
mis-read the timing as "closed"; the deep jar analysis corrected me, and I re-verified it).
Corrections, all confirmed against `fabric-loader-0.19.3.jar` + `sponge-mixin-0.17.3`:

- **Timing window is REAL (verified).** `Knot.init` order is `FabricMixinBootstrap.init`
  (147) → `finishMixinBootstrapping` (148, advances Mixin to DEFAULT via `gotoPhase`) →
  `initializeTransformers` (150, just *instantiates* the transformer) → `invokeEntrypoints
  ("preLaunch")` (156). Phase being DEFAULT does **not** close the window: config selection
  is lazy — `MixinProcessor.checkSelect` re-runs `select()` while
  `Mixins.getUnvisitedCount() > 0 && transformedCount == 0` (I verified this bytecode), and
  `transformedCount` only increments after the first **game class** is transformed, which
  is *after* PreLaunch (PreLaunch runs "several seconds before" the game main). So a late
  `addConfiguration` at PreLaunch **is** picked up. This is exactly how dynamic-mixin Fabric
  mods already work.
- **AW API in the plan was wrong.** Fabric 0.19.3 has **no** `FabricLauncherBase.AccessWidener`
  — AW is the **ClassTweaker** system (`FabricLoaderImpl.getClassTweaker()`/`loadClassTweakers`,
  verified present). It is not sealed: `ClassTweakerReader.create(getClassTweaker()).read(
  lbAwBytes, ns)` feeds entries late, honored at transform time by `FabricTransformer` —
  *provided the target class hasn't been read yet* (same window). **Bonus:** this
  auto-dodges the vanilla LVTGeneratorError crux — `MixinServiceKnot.getClassBytes` routes
  Mixin's `ClassInfo` metadata through the AW/ClassTweaker pass, so **no bytecode-provider
  patch is needed on Fabric** (unlike vanilla).
- **Only ONE non-mod hook is real; drop the other.** The plan's "synthetic PreLaunch
  entrypoint" (option i) is **not** non-mod — entrypoints are `ModContainerImpl`-keyed, so
  registering one means being a discovered mod. The genuinely non-mod route is (option ii)
  **premain `Instrumentation`-rewrite of `Knot`/`FabricMixinBootstrap`** — verified viable
  because those load on the app/system classloader (`LoaderUtil.verifyNotInTargetCl`), so a
  premain transformer sees them. It is **fragile / loader-version-specific** (0.19.3's
  `init()` body shifts across versions).
- **Classloader identity — real hazard, mitigation verified.** `KnotClassLoader` is
  isolated (child-first, `parent=DummyClassLoader`); LB classes on the agent loader are
  invisible to Knot. Fix: `KnotClassDelegate.addCodeSource(Path)` → `addUrlFwd(URL)` is
  `synchronized` with no frozen guard, callable late — add LB's jar this way.
- **Kotlin runtime — the plan MISSED this (real gap).** LB is Kotlin and `fabric.mod.json`
  hard-depends on `fabric-language-kotlin` (verified). On a live install *without* FLK,
  LB won't link. The agent must inject **kotlin-stdlib + fabric-language-kotlin** onto Knot
  alongside LB's jar.

**NeoForge — basically a non-starter for *non-mod* injection (reasoned, NOT jar-verified).**
ModLauncher builds an immutable JPMS `ModuleLayer` from `ServiceLoader`-discovered transform
services (incl. `MixinServiceModLauncher`) and seals it before `Launcher.run()`; a premain
agent runs before that with no post-seal API to add a module/transformation service.
Realistic outcomes: become a discovered `ITransformationService` (defeats "non-mod"), or
don't inject. **Needs its own jar-level spike before this is a finding.**

**Is it worth it vs the native mod? Weak.** The agent must reproduce at runtime, reflectively,
everything `fabric.mod.json` declares — classpath insert (LB jar **+ Kotlin + FLK**), both
mixin configs via `addConfiguration`, AW via ClassTweaker — through a version-brittle Knot
rewrite. It buys **only** "not a discovered mod / not in the mod list." Both loaders already
boot LB in-world via the mod path. **Gate 0: confirm with scorpion that "attach without
installing" is a hard requirement** before any build; otherwise the mod path dominates.

### Revised gate — trimmed kill-shot (supersedes the full spike)

1. **Gate 0 (free): confirm the use case with scorpion.** If "must not be a discovered mod"
   isn't required, stop — use the mod path on loaders.
2. **One-assertion Fabric spike:** the only genuinely-uncertain empirical bit is whether a
   late `addConfiguration` at PreLaunch actually applies to `Minecraft.<init>` on a live Knot
   (println fires) without the first-transformed-class race. Prove *that single assertion*;
   add the AW/ClassTweaker sub-check only if it's green.
3. **NeoForge** gets its own separate jar-level spike, only if Fabric is green and scorpion
   still wants it.

## Milestones (once greenlit — not now)

SPIKE (Fabric: one mixin applies via defer) → confirm use case → Fabric back-end (classpath
insert + addConfiguration hook + AW pass) → launch-confirm full LB on a Fabric install →
NeoForge spike → NeoForge back-end (or document "mod path only").
