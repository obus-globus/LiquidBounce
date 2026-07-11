# Koda Handoff — Agent-Injection Project: Strategic State & Resume Guide

> This is the **management/strategic** handoff (Koda's perspective): the arc, the
> decisions, what genuinely works vs what is blocked, and where to resume.
> For the **deep technical detail** (every fix, converter internals, the exact
> shader-lifecycle root cause), see Obus's docs — start at `docs/RESUME.md` and the
> per-topic dirs listed under "Doc map" below.
>
> Branch: `feat/vanilla-agent`. All work is here. Nothing merged to any main branch.

---

## 1. The goal (and how it grew)

Original ask (scorpion): make LiquidBounce **modloader-independent** — run the full
client via a Java agent, not just as a Fabric/NeoForge mod. It expanded, in order,
into:

1. **Vanilla PoC** — run full LB on unmodified vanilla MC 26.2 via a Java agent. ✅
2. **All three loaders** — the agent method working on vanilla, Fabric, NeoForge. ✅
3. **Self-contained artifacts** — one drop-in jar per target, no Gradle/dev paths. ✅
4. **Offline MCEF** — bundle the native `libcef` so it works with no network. ✅
5. **No-flag injection** — launch MC with no flags, inject after the fact:
   - **Watcher / early-attach** — ✅ **works, full UI, real modules.**
   - **Attach into an already-*fully-loaded* game** (needs the "converter") — ⚠️ **the active frontier; blocked on one systemic issue (see §4).**

Key early finding that de-risked everything: **MC 26.2 ships Mojmap-named and Fabric
has no intermediary for it** → dev names = prod names, **no refmap needed on any
loader**. (scorpion's intuition, verified 4 ways — see `docs/agent-injection-mapping-test/`.)

---

## 2. Scoreboard — what is DONE and WORKING

All of these are proven, verified (pixel-verified where UI is involved), and
reproducible. **These are the shippable results.**

| Capability | State | Notes |
|---|---|---|
| Vanilla full LB via pure `-javaagent` | ✅ | No launcher needed. Synthetic-class generation solved by injecting Mixin synthetics into the system loader. |
| Fabric full LB via agent (DEFER into Knot's live Mixin) | ✅ | LB absent from mod list; menu + Fly proven. |
| NeoForge full LB via agent (DEFER into FML's live Mixin) | ✅ | Absent from mod list; menu + Fly proven. |
| Self-contained single-jar agent per target | ✅ | Mechanical dep enumeration by maven group; extract-to-temp; jar-relative reads. |
| Offline MCEF (`-PbundleMcefNative`) | ✅ | Opt-in; bundles native `libcef`, sets `PROVIDED_JCEF_PATH`; verified zero-download on all 3. |
| **Watcher / early-attach (no-flag injection)** | ✅ | **Run watcher jar → it attaches to a launching MC in the early window → full LB, MCEF menu renders, real modules work.** ~98% mixin coverage (misses only classes loaded in the first ~1s). **This is the working no-flag answer.** |
| Dynamic attach into a running MC is *possible* at all | ✅ | Plain `VirtualMachine.attach` works on JDK 25 (JEP 451 only *warns*, doesn't block). No ptrace needed. |

**Strategic note:** the **watcher already delivers the "no-flag injection" goal with a
fully working UI**, because at early-attach LB is present when MC's startup
`ShaderManager.apply` runs, so its custom render pipelines compile at the right moment.
This is the crucial contrast with the blocked path below.

---

## 3. The converter (schema-neutral mixin backend) — BUILT, mechanically proven

To inject into a **fully-loaded** game you must re-apply LB's mixins to
**already-loaded** classes via `retransformClasses`, which **forbids schema changes**
(no adding fields/methods/interfaces). The converter rewrites LB's mixins into a
schema-neutral form (added members relocated to external sidecars + per-instance
state + reflection) so retransform accepts them.

- **Mechanically complete and verified at scale: 146/146 real LB mixin targets convert
  schema-legally, 0 edge cases.** Handles every injector family (`@Inject`,
  `@Unique`, `@WrapOperation`/MixinExtras invokedynamic, `@Shadow`-private via
  reflection, interface-adders, cross-target `@Unique` method/field dispatch, etc.).
- **Residue (deferred to on-load path, low value):** 5 hard `@Local` sites
  (`LocalIntRef` write-back etc. — powering only BetterTab/BetterChat/one niche
  firework exploit + a GC optimization) and the `ItemCooldownsAddition.Entry` type-move.
- Full detail + all ~15 in-world fixes: see Obus's converter/frontier docs.

The converter is a **real, reusable achievement** independent of whether the
fully-loaded-attach path ends up shipping.

---

## 4. THE ACTIVE FRONTIER (what to resume) — attach into a fully-loaded game

**Current honest state: LB *injects and fully initializes* into a live, no-flag,
already-running MC (all mixins converted, render hooks fire ~30/sec, CEF paints a
valid texture), BUT the CEF/custom-shader UI does NOT composite on screen.**

### The root cause (definitive, evidence-based — NOT a converter bug)

LB compiles its custom render pipelines inside MC's **startup** `ShaderManager.apply`
(via `MixinShaderManager@TAIL` → `ClientRenderPipelines.precompile()` using LB's own
`ClientShaders` source provider). On attach-into-a-fully-loaded game, **that startup
step already ran before LB attached** → the JCEF pipeline
(`jcef/bgra_blurred_texture`, custom fragment shader `bgra_pos_tex_color`)
lazy-compiles later with MC's **default** shader source, which lacks LB's shaders →
`Couldn't find source for FRAGMENT shader` → pipeline invalid → the CEF quad draws
nothing every frame. The **theme aurora backdrop renders** only because it uses a
**vanilla** shader (`GUI_TEXTURED`) through the identical draw path.

Everything else is proven working: converted mixins, render events, `BrowserRenderer.render()`,
the reflected `guiRenderState`/`scissorStack` draw path, the pipeline objects. It is
**one systemic late-attach lifecycle gap**, localized precisely.

### Why the naive fix doesn't (yet) work

Calling `precompile()` post-attach clears the "not found" error but then hits
`Duplicate bind name 'Globals' in bind group layout` — the pipeline already cached a
failed lazy-compile and registered bind groups; re-compiling collides with that state.

### Candidate fixes to try on resume (all uncertain, all fight the late-attach lifecycle)

1. Register LB's `ClientShaders` source with the ShaderManager **and reset the
   failed pipeline / bind-group cache** *before* the first `BrowserRenderer` draw, so
   the first (and only) compile succeeds with LB's shaders.
2. Force a **clean full shader-manager reload lifecycle** post-attach (re-run
   `ShaderManager.apply` so `MixinShaderManager@TAIL` fires) — but resolve the cached
   bind-group collision first.
3. Broader: get LB's pipeline compilation to happen at the correct lifecycle point,
   i.e. make the attach path replay the relevant slice of MC's startup resource/shader
   reload with LB present.

This is a member of the **"late-attach ordering" class** of issues that recurred
throughout (frozen registries, tick-ordering, this shader lifecycle). Expect it to be
fiddly and possibly to surface further lifecycle couplings.

---

## 5. Strategic recommendation (Koda's honest call)

- The **watcher / early-attach path is the working no-flag deliverable.** If the goal
  is "launch MC with no flags, then inject and get a fully working LB," **the watcher
  already does it** (full MCEF UI, real modules), because it beats the shader-compile
  window. Prefer it for anything user-facing.
- The **fully-loaded-attach + converter path** is a deep, real achievement (injection
  + full mixin conversion + init + hooks + CEF paint all proven) whose **only**
  remaining blocker is the shader-lifecycle gap in §4. scorpion chose to **continue**
  this later — so §4 is the precise resume target.
- Don't reopen module-by-module trial-and-error. The functional-render failure is
  fully localized to §4; fixing that one lifecycle issue is what could unlock the CEF
  UI + HUD + modules together (they share the deferred-GUI draw path).

---

## 6. Process lessons (important — these cost us real credibility mid-project)

- **Verify screenshots at the pixel level before claiming anything.** Two over-claims
  happened by trusting log text / a "LiquidBounce"-labeled frame that was actually
  (a) LB's native *crash dialog* over a vanilla screen, and (b) a theme *backdrop*
  with no UI. Koda now opens every delivery image before relaying. Do the same.
- **"Render hooks fire" ≠ "a feature renders."** Prove visible output, not just that
  an event dispatched.
- **Don't explain away absence with unverified excuses** ("headless CEF", "player-only
  targeting"). Both were wrong and were caught. Instrument the mechanism instead.
- **Environment gotchas that repeatedly wasted cycles** (see Obus's env doc): shared
  Xvfb `:99` retains **stale frames and leftover native dialogs** from prior runs —
  use a fresh display and capture **by window ID** (verify `_NET_WM_PID`). Set
  **`CI=1`** so LB's error handler logs instead of spawning blocking modal dialogs.
  Use `--add-opens java.base/java.lang=ALL-UNNAMED`. **Keep MC persistent/detached** —
  one-shot harnesses kill MC before you can capture/probe.

---

## 7. Doc map (where the detail lives)

- **`docs/RESUME.md`** — Obus's master technical index / "start here" (read first).
- `docs/agent-injection-mapping-test/` — the Mojmap/no-refmap proof.
- `docs/vanilla-agent-spike/`, `docs/vanilla-pure-agent-spike/`,
  `docs/vanilla-agent-selfcontained/` — vanilla path.
- `docs/fabric-agent-spike/`, `docs/fabric-agent-selfcontained/` — Fabric path.
- `docs/neoforge-agent-probe/`, `docs/neoforge-agent-selfcontained/` — NeoForge path.
- `docs/vanilla-dynamic-attach-spike/` — the watcher / no-flag attach path.
- Converter + fully-loaded-attach frontier docs — see `docs/RESUME.md` for the exact
  filenames (Obus is authoring these alongside this handoff).
- `docs/offline-mcef-flag.md` — the `-PbundleMcefNative` flag.
- Older plans (superseded but useful history): `agent-injection-productionization-plan.md`,
  `agent-on-all-loaders-plan.md`, `three-target-multiloader-plan.md`, `vanilla-agent-plan.md`.

**Resume in one line:** the watcher path works and ships the no-flag goal; the
open work scorpion wants to continue is the **shader-lifecycle fix in §4** to make the
CEF UI composite on the fully-loaded-attach path.
