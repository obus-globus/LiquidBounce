# LiquidBounce across Fabric + NeoForge + Vanilla-agent — plan

Branch: `feat/vanilla-agent`. **Plan only — no building until reviewed.**

## Read this first (the interpretation you can veto in one read)

**This repo is already the NeoForge port of a Fabric mod.** It's a working *2-target
multiloader*: the root project is the **Fabric** build (fabric-loom), and `:neoforge`
is a subproject that reuses the same shared `src/main/{java,kotlin,resources}` +
`src-theme/resources`, converts the AccessWidener to an AccessTransformer, and runs its
own client. **Both loader targets already compile** (this session produced
`build/libs/liquidbounce-0.38.1.jar` and `neoforge/build/libs/liquidbounce-neoforge-0.38.1.jar`).

So "get LB working for Fabric and NeoForge" almost certainly does **not** mean "use the
agent on those loaders" — a real loader applies mixins + the AW natively; the agent
exists only because *vanilla has no loader*. The most useful reading is:

> **Productionize into a clean, tested three-target multiloader tree** where the same
> shared `src/main` produces three *launch-confirmed* artifacts:
> - **fabric** — loader build (already exists)
> - **neoforge** — loader build (already exists)
> - **vanilla** — agent build, promoted from the throwaway `docs/vanilla-agent-spike/`
>   launcher into a real `:vanilla` Gradle subproject.

The agent target reuses everything we proved; the two loader targets are verified, not
assumed.

**Alternative interpretations (name them so scorpion can pick):**
- **(B) An agent for loader-*present* installs** — inject LB via `-javaagent` into an
  existing Fabric/NeoForge install rather than as a mod. This is a *different* product
  (compat layer over another loader's Knot/ModLauncher classloader) and much harder;
  our vanilla agent assumes *no* competing loader (we had to exclude fabric-loader's
  rival Mixin services). Only pursue if scorpion explicitly wants agent-on-loader.
- **(C) Just fix/ship the two loader builds** (no vanilla productionization) — if the
  vanilla agent stays a research artifact. Cheapest; loses the third target.

Everything below assumes interpretation **(A)** unless scorpion says otherwise.

## Current state (verified facts, not assumptions)

- **Shared code is untouched by the vanilla work.** `git diff feat/neoforge-support..HEAD`
  over `src/`, `neoforge/`, `gradle/`, `settings.gradle.kts`, `build.gradle.kts` is
  **empty** — all 31 changed files live under `docs/vanilla-agent-spike/`. The one
  `MixinLocalPlayer` edit made while chasing the fallback was reverted; the real fix is
  agent-side. **The loader builds cannot have regressed from vanilla work** (nothing
  shared changed) — but that's an argument, not a launch test (see §2).
- **Structure:** root = Fabric; `:neoforge` = subproject (`neoforge/build.gradle.kts`)
  pulling `src/main` via `srcDir`, plus its own `neoforge/src/main` companions.
- **Dependency shape today:** Fabric `api+include(mcef)` + `viafabricplus` + DJL + jij
  {mcAuthlib, lwjgl-egl, httpServer, discordIpc, polyglot(GraalJS)}. NeoForge
  `jij(mcef-neoforge)` + mirrored jij list; Via is compiled-in but guarded off
  (`isModLoaded("viafabricplus")` always false). Vanilla (throwaway) reused the Fabric
  `mcef` jar + kept Via/DJL on the classpath for linking.

## §1 — Promote the throwaway vanilla launcher to a real `:vanilla` subproject

Goal: one command builds a **fat agent jar** that runs LB on unmodified vanilla MC 26.2,
folding in everything proven in the spike. New subproject `:vanilla` (sibling of
`:neoforge`), same `src/main` reuse pattern.

Fold in (all already working in the throwaway):
- **Standalone Mixin service** over `java.lang.instrument`
  (`VSpikeService`/`Bootstrap`/`GlobalProps`/`ClassProvider`/`BytecodeProvider`).
- **In-agent AccessWidener** applied both in the transformer **and** in the bytecode
  provider (the metadata fix — without it, injectors on AW-widened private methods fail).
- **Transforming classloader** that generates Mixin synthetic classes on demand
  (`@ModifyArgs` etc.) — a `-javaagent` alone can't; plus the self-load whitelist
  (`net.minecraft`, `com.mojang.{blaze3d,math,realmsclient}`, `net.ccbluex`,
  `org.spongepowered.asm.synthetic`) and resilient per-class fallback.
- **MixinExtras bootstrap after transformer creation**; force `gotoPhase(DEFAULT)`;
  ASM `Implementation-Version` in the fat-jar manifest (or Mixin rejects `JAVA_25`);
  bundle Fabric's `sponge-mixin:0.17.3` + unshaded ASM ≥9.7; exclude the classpath's
  `fabric-loader` + dep `sponge-mixin` (rival Mixin services).

Build-task shape (to design, not yet build):
- `:vanilla` compiles `src/main` (+ a small `vanilla` source set: `VanillaPlatform`,
  the corrected `Platform` service, the agent classes) against **Mojmap** MC (no remap —
  26.2 ships deobfuscated), producing a shadow/fat jar with manifest `Premain-Class`.
- A `:vanilla:runClient` dev task launching vanilla MC with `-javaagent`, reusing the
  Mojmap client jar + piston libs (documented cache resolution from `run.sh`).
- **Open question to resolve in the plan-build:** whether `:vanilla` uses loom (for the
  Mojmap MC dependency + run config) or a plain JVM/shadow setup. Loom gives the MC dep
  and `runClient` for free but is Fabric-flavored; a plain setup is cleaner but must
  re-implement MC dependency resolution. Recommend: loom with `runClient` overridden to
  drop Fabric and add the agent — least new infrastructure.

## §2 — Fabric + NeoForge: verify they actually build **and launch** (don't assume)

We compiled both this session but **never launch-tested** either. Verify-plan:
- `./gradlew :build` (Fabric) and `:neoforge:build` — confirm green incl. `detekt`,
  `checkLoaderPurity`, `checkMixinDivergence`.
- **Launch each** via the capture pipeline (`docs/minecraft-capture.md`): `:runClient`
  (Fabric) and `:neoforge:runClient` — boot to the LB menu + into a world, screenshot,
  confirm 0 mixin errors. This is the step we skipped; treat "compiles" ≠ "runs".
- **NeoForge caveat (real):** NeoForge target needs `mcef-neoforge:3.3.2-26.2-SNAPSHOT`,
  which is **only in local `~/.m2`** (not on `maven.ccbluex.net`) — CI is red for this.
  The plan must state the NeoForge launch depends on the local artifact (or building it
  from `obus-globus/mcef@neoforge-26.2`), and that this is a *known* gap, not a
  regression.
- **Regression check:** since nothing shared changed, expect none — but the launch test
  is the proof. Explicitly diff shared `src/main` and the two `*.mixins.json` vs the
  `feat/neoforge-support` base as part of verification.

## §3 — MCEF / Via / DJL bundling, consistent across all three targets

- **MCEF:** Fabric `include(mcef)`; NeoForge `jij(mcef-neoforge)`; **vanilla** should
  `jij`/shade a browser artifact. The spike reused the *Fabric* `mcef` jar on vanilla and
  it worked (software-rendered). Decide the canonical vanilla MCEF artifact (Fabric mcef
  vs a loader-agnostic build) and bundle it consistently. Note the software-GL `libcef`
  crash is environmental, not a bundling issue.
- **Via:** Fabric uses ViaFabricPlus (works); NeoForge compiles it in but guards it off
  (ViaForge not updated to 26.x). Vanilla: same as NeoForge — Via classes must **link**
  (bundle Via or stubs) but `isModLoaded` → false, so it degrades. State the policy:
  Via *functional* on Fabric only; *present-but-inert* on NeoForge + vanilla until
  ViaForge/an agent-Via path exists.
- **DJL:** compiled-in on all three (deeplearn modules); confirm the `jij` list is
  mirrored into `:vanilla` (it already is mirrored Fabric↔NeoForge).

## §4 — The two mixin "fixes" are agent-only; confirm no loader impact

- The `LVTGeneratorError` fallbacks (`MixinLocalPlayer` `sendPosition` / `MixinChatComponent`
  `addMessageToDisplayQueue`) were **agent-side** — root cause was our bytecode provider
  feeding Mixin un-widened metadata. **No LB source changed** (the `index=12` experiment
  was reverted to `name="rot"`). On Fabric/NeoForge, loom/AT apply the AW so
  `ClassInfo.findMethod` sees the widened method natively — these injectors already work
  there. **Nothing to mirror.** Verification: the §2 launch tests would exercise these
  injectors; confirm no LVT errors on the loaders.

## §5 — Shared vs per-target, and CI/build ergonomics

- **Genuinely shared** (`src/main`): all features/modules, ~150 mixins, the `Platform`
  interface, events, the accesswidener, the mixin configs. Loader-agnostic (0 loader
  imports).
- **Per-target:** loader entry/metadata (`fabric.mod.json` / `neoforge.mods.toml` / agent
  manifest), the `Platform` impl (`FabricPlatform`/`NeoForgePlatform`/`VanillaPlatform`),
  loader companion mixins (unpatched-vanilla shapes for Fabric+vanilla; patched shapes
  for NeoForge), and the loader plumbing (agent classloader/service for vanilla).
- **CI/ergonomics:** three targets = three build+launch jobs. The agent target has no
  published MC-loader dep but needs the Mojmap MC + a documented cache/asset resolution;
  CI for `:vanilla` must reproduce `run.sh`'s classpath assembly. Keep `MixinDivergenceCheck`
  running on all shape-divergent targets. Decide whether `:vanilla` is in the default
  `build` aggregate or opt-in (it currently isn't a subproject at all).

## Milestones (once greenlit — not now)

P1 launch-verify Fabric + NeoForge (baseline truth) → P2 scaffold `:vanilla` subproject
+ fat agent jar → P3 fold in agent internals + `VanillaPlatform` → P4 launch-confirm all
three from committed build tasks → P5 dependency-bundling consistency + CI.

## Explicitly NOT in this plan

Fixing the software-GL MCEF crash; ViaForge/agent-Via functional protocol translation;
publishing `mcef-neoforge` upstream; interpretation (B)'s agent-on-loader compat layer.
