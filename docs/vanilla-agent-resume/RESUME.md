# LiquidBounce Vanilla-Agent Injection — RESUME (start here)

**Branch:** `feat/vanilla-agent`  ·  **Base commit at pause:** `f05ddf4c6`  ·  **MC:** 26.2  ·  **JDK:** 25.0.3
**Status:** paused by scorpion, to resume later. This is the **technical** handoff.
**Strategic / management handoff:** [`docs/KODA-HANDOFF.md`](../KODA-HANDOFF.md) (Koda) — the arc, what-works-vs-blocked scoreboard, the shader-lifecycle frontier framed for resume, process lessons. That doc points here as the technical index; this doc points there for the strategic framing. Read both.

This document is the index + mental-model bootstrap. Read it top to bottom, then dive into the numbered docs.

---

## 1. Goal

Inject **full LiquidBounce (Nextgen, MC 26.2)** into an **unmodified vanilla Minecraft** via a Java **`-javaagent` / dynamic-attach**, with no launcher and no Fabric/NeoForge mod loader — across all three loader shapes, and ultimately into an **already-running, no-flag** client (attach to a live PID).

Two fundamentally different injection timings emerged, and the distinction is the key to everything:

- **Early / on-load path ("watcher", premain, or attach-before-classes-load).** Mixins apply *as classes load* (raw Mixin output, schema changes allowed). LB is present when MC's startup lifecycle runs. **This path renders the full working LB UI + real modules.** ✅ This is the one that actually works end-to-end for UI.
- **Late-attach / converter path.** Attach to a fully-loaded, already-running client. Mixin targets are already defined, so mixins cannot be applied normally (`retransformClasses` forbids adding/removing members). The **schema-neutral converter** rewrites each mixin into a retransform-legal form (relocate added members to sidecars + external state + reflection). ✅ LB fully injects, initializes, enters a world, enables modules, runs without crashing. ❌ **The CEF-composited UI (menu / ClickGUI / HUD foreground) does not render** — blocked on one precise, well-understood root cause (the shader-lifecycle frontier, below).

---

## 2. The journey in brief

1. Rebased LB onto MC 26.2, got Fabric + NeoForge boot-verified (pre-this-session; see git history / task list).
2. Built three **self-contained agents** (vanilla pure-agent, Fabric, NeoForge) that bundle LB + deps and apply mixins **on-load** via a standalone Mixin service (`vspike.*`) + AccessWidener. → `docs/vanilla-agent-resume/01-deliverables.md`
3. Added `-PbundleMcefNative` so MCEF/Chromium runs **offline** (no download). Captured the LB MCEF menu on all three loaders.
4. Proved the **watcher / early-attach** no-flag path: a separate injector attaches during MC boot, before game classes load, and the full pure-agent pipeline applies on-load. **Full LB UI renders.**
5. Investigated **attach-into-a-fully-loaded-game** ("Barrier ①/②"). Barrier ① (can't even attach to a live MC) turned out to be a measurement artifact — dynamic attach works on a live no-flag MC at any time. Barrier ② (retransform forbids schema changes) is real → motivated the converter.
6. Built the **schema-neutral converter** and verified every mixin category live. Scaled to **146/146** LB mixin targets converting clean. → `docs/vanilla-agent-resume/02-converter-architecture.md`
7. Built the **attach-to-running artifact** (`FullInjectAgent`) and drove it end-to-end into a live no-flag MC: LB initializes, enters a world, enables modules, runs without crashing. Fixed **~20 distinct late-attach bugs** to get there. → `docs/vanilla-agent-resume/03-attach-frontier.md`
8. **Frontier reached:** the CEF UI does not composite on the converter path. Diagnosed to a single root cause: LB's render-pipeline shaders are compiled inside MC's **startup `ShaderManager.apply`**, which the late attach misses. → `docs/vanilla-agent-resume/03-attach-frontier.md` §"Final root cause".

---

## 3. What works vs what's blocked

| Capability | Watcher / early-attach | Converter / late-attach |
|---|---|---|
| Attach to no-flag MC | ✅ (early boot window) | ✅ (any time, live PID) |
| Mixins applied | ✅ on-load (raw) | ✅ 146/146 via converter |
| LB bootstrap + full init (managers, modules, configs) | ✅ | ✅ |
| MCEF/Chromium init + browser page load (HTTP 200) | ✅ | ✅ (browser paints a valid texture) |
| Enter world, enable modules, run without crash | ✅ | ✅ |
| LB render *events* fire (`WorldRenderEvent`/`OverlayRenderEvent` ~30/s) | ✅ | ✅ (instrumented, 6545 events) |
| Native theme backdrop (vanilla-shader draw) | ✅ | ✅ (aurora renders) |
| **CEF-composited UI (menu/ClickGUI/HUD) + custom-shader render** | ✅ | ❌ **BLOCKED** (shader-lifecycle) |

> The converter path is a genuine, deep achievement — full LB *runs* in a live no-flag game. The remaining gap is **one lifecycle coupling**, not a converter defect. Every converted mixin, hook, and the CEF texture paint work; only LB's *custom render-pipeline shaders* fail to compile because their compile step is wired to a startup lifecycle the late attach missed.

---

## 4. Document map

- **`RESUME.md`** (this file) — index + mental model + resume priorities.
- **`01-deliverables.md`** — the working artifacts with exact build + run commands: three self-contained agents, the `-PbundleMcefNative` flag, and **the watcher/early-attach path (the one that renders the full UI)**. Survives a cold start.
- **`02-converter-architecture.md`** — the schema-neutral converter, full architecture, every category, and the 5 hard `@Local` residue.
- **`03-attach-frontier.md`** — **the active resume point.** All ~20 late-attach fixes chronologically, then the final root cause in full detail, then candidate fixes to try.
- **`04-environment-gotchas.md`** — VM/display/JDK gotchas that will bite a cold resume; where evidence lives.
- **`converter-core/`** — the *culmination* source (the most advanced versions, from the live end-to-end runs): `FullInjectAgent.java`, `RetransformConverter.java`, `AwReflect.java`, and `run-menu2world.sh` (the menu→init→enter-world harness). These are the canonical converter sources; older copies live under `docs/vanilla-dynamic-attach-spike/barrier2-converter/converter-core/src/`.
- **`evidence/`** — key screenshots: `live-inworld.png` / `live-tracers.png` (full LB injected + running in a *"LiquidBounce … Singleplayer"*-titled world, real in-world frame), `live-1-title.png` (native theme aurora backdrop = LB rendering, but no CEF foreground; the post-hoc `precompile()` experiment produced a visually identical theme-only frame).

---

## 5. Resume priorities (do these, in order)

1. **The shader-lifecycle frontier** (`03-attach-frontier.md` §"Candidate fixes"). This is the single thing between "LB fully injects and runs" and "the CEF UI actually composites" on the converter path. Two candidate approaches, both uncertain, both documented:
   - (a) Register LB's `ClientShaders` source with MC's ShaderManager **and reset the failed pipeline/bind-group compilation cache** *before the JCEF pipeline is first drawn*, then `precompile()`.
   - (b) Force a clean full ShaderManager reload lifecycle post-attach (fix the `reloadResourcePacks()` throw first), so `MixinShaderManager@TAIL → precompile()` runs at the right time.
2. If (1) proves systemic (the late-attach lifecycle can't cleanly deliver compiled render pipelines), **bank the converter result and rely on the watcher/early-attach path** for the CEF-composited UI (it works because it's present when `ShaderManager.apply` runs). Document that decision.
3. Optional converter polish: the **5 hard `@Local`** residue (`02-converter-architecture.md`) and re-integrating the `converter-core/` sources as a proper Gradle module.

---

## 6. One-paragraph "what is this actually"

`FullInjectAgent` (an `agentmain` dynamic-attach agent) stages the bundled LB jar + deps onto the system classloader, boots a **standalone Sponge Mixin service** (`vspike.VSpikeService`) with LB's mixin configs + AccessWidener, and for each of LB's 146 mixin targets: runs the Mixin transformer to get `X` (mixin output), diffs it against the original `O`, and **converts** `X` into a retransform-legal `target'` + a same-package `$$LBSidecar` holding the relocated added members (via `RetransformConverter`). Already-loaded targets are patched with `retransformClasses`; future ones via an on-load `ClassFileTransformer`. LB callers are rewritten to route through sidecars/reflection (`AwReflect`). Then it manually fires `ClientStartEvent` to kick LB's bootstrap. The result: full LB runs in a live no-flag MC. The one thing it can't do is compile LB's custom render-pipeline shaders, because that is wired to MC's startup `ShaderManager.apply` which already ran.
