# 03 — The attach-into-a-fully-loaded-game frontier (ACTIVE RESUME POINT)

This is where the work paused. `FullInjectAgent` (dynamic-attach, converter path) drives full LB into a **live, already-running, no-flag** MC. Getting from "attach" to "LB fully runs in a live world" required fixing **~20 distinct late-attach bugs**, listed chronologically below (each executing code path exposed the next). Then: the **final root cause** that blocks the CEF UI, and **candidate fixes** to try on resume.

The harness: `converter-core/run-menu2world.sh` — boots a bare MC to the **menu**, attaches `full-agent.jar` (LB inits calmly), then programmatically **enters a world** (`WorldOpenFlows.openWorld`), then enables modules. (Menu-first is deliberate — see fix #10.)

---

## The ~20 fixes, chronologically (bug → fix)

1. **`h$<clinit>` — illegal method name.** The converter relocated a mixin-added static-field initializer as a method literally named `h$<clinit>` (`ClassFormatError`, silently swallowed). **Fix:** merge an added `<clinit>` into the sidecar's own `<clinit>` (converter §4).

2. **Cross-package sidecar access + signed-jar define.** Sidecars first lived in `lbconv/`; relocated handler bodies then couldn't reach package-private target internals (e.g. `GuiRenderState$Node`). **Fix:** put the sidecar in the **target's own package**, and define it with the target's **ProtectionDomain** (MC jar is signed → null PD gives `SecurityException: signer information does not match`).

3. **Private `@Shadow` method via `invokespecial`.** `Minecraft.startUseItem()` (private) called from a relocated body → illegal cross-class. **Fix:** reflective invoker (converter §7).

4. **`TextColor.hashCode()` → `StackOverflowError`.** Per-instance State map was a `ConcurrentHashMap`, which calls `self.hashCode()`; `TextColor.hashCode()` reads the relocated field → `getState → map.get → hashCode → …`. **Fix:** identity-keyed store (`synchronizedMap(IdentityHashMap)`) (converter §1).

5. **Static-field visibility.** A relocated `private static` field (`Utils$$LBSidecar.CURRENT_URL`) accessed cross-class from `target'` → `IllegalAccessError`. **Fix:** relocated static fields made `public`, non-`final` (converter §2).

6. **`RenderSystem called from wrong thread`.** LB's `ClientStartEvent` handler does render-thread work; the manual kick ran on the Attach Listener thread. **Fix:** fire `ClientStartEvent` via `Minecraft.execute(Runnable)` on the **main thread**. (LB's `<init>` hook that normally fires it already ran pre-attach, hence the manual kick.)

7. **`AccountManager` → `Minecraft.user` (private field, already-loaded).** Fatal `IllegalAccessError` — the AccessWidener can't widen an already-loaded class's field (retransform bans field-modifier changes). **Fix:** reflection-rewrite LB's *own* direct accesses to non-public members of already-loaded MC classes → `AwReflect` (this is the seed of converter §10; it grew to cover fields, methods, ctors).

8. **Private constructor of an already-loaded class.** `RenderPipeline$Builder.<init>()` (private) → `IllegalAccessError`. **Fix:** reflective `newInst` trampoline (converter §11).

9. **Registries frozen.** `ModuleHitFX.<clinit>` registers a custom sound (`liquidbounce:bonk`) into MC's registries, which are **frozen** after startup (long before late attach). **Fix:** reflectively clear `MappedRegistry.frozen` on all registries before the bootstrap kick (converter §"registry unfreeze"). *(See also #14 — must re-freeze before world-load.)*

10. **Tick-ordering hazard.** Attaching to a **ticking** game: converted mixins activate immediately, and the tick loop exercises them (`ClientInput.hasForwardImpulse` → `ModuleSprint` → `ModuleScaffold.<clinit>`) **before LB's async init finishes** → LB module/config code runs uninitialized → NPE. **Fix:** **menu-first-then-enter-world** — attach at the menu (no ticking; LB inits calmly), then programmatically enter a world so ticks hit an already-initialized LB.

11. **`TextColor` fixed but the class-type wall appears.** LB references package-private *already-loaded* MC classes **by type** (`GuiGraphicsExtractor$ScissorStack` in `ThemeBackground`) → `IllegalAccessError` on the type. Confirmed: `retransformClasses` **rejects class-modifier changes** too, so these can't be widened. **Fix:** **inaccessible-type erasure** — string-based `AwReflect` (owner/param types as strings via `Class.forName`, no access check) + erase all CHECKCASTs to inaccessible types (converter §10).

12. **Systematic retransform `VerifyError`.** `Gui`, `ClientPacketListener`, `ClientCommonPacketListenerImpl` failed retransform with a bare `VerifyError` (they *linked* fine offline). **Fix:** **correct stack-map frames** — real `getCommonSuperClass` via class-byte hierarchy walk (converter §12). Dropped fails 6 → 1.

13. **Nest/Record/PermittedSubclasses.** `AvatarRenderer`/`MinecraftClient`/`Level` → `attempted to change the class NestHost, NestMembers, Record, or PermittedSubclasses attribute`. **Fix:** preserve those attributes from `O` (converter §13).

14. **`Tags already present before freezing` on world-load.** The registry unfreeze (#9) breaks world-load, which re-freezes registries. **Fix:** re-freeze all registries (`MappedRegistry.frozen = true`) **before** `openWorld` — MC's `freeze()` early-returns when already frozen, avoiding the conflict.

15. **`RECURSIVE_SCREEN_OPENING` null.** `Gui.setScreen` (during world-open) → NPE: the mixin-added static `ScopedValue` was null. Its init was **appended to `Gui`'s existing `<clinit>`** (modified-clinit), which won't re-run on an already-loaded class. **Fix:** clinit-**delta** copy into the sidecar `<clinit>` (converter §4).

16. **`Entity.liquid_bounce$isClientPlayer()` — cross-target `@Unique` method.** Added to base `Entity`, called from `Player`/`LivingEntity` subclass mixin sidecars → `NoSuchMethodError`. **Fix:** **GADDED** global table + hierarchy walk (converter §9).

17. **`Screen.handler$zdn000$init` — GADDED collision.** GADDED keyed by name+desc alone collided (same handler name on multiple targets), so the call wasn't routed and the removed method wasn't found. **Fix:** key GADDED/GFIELD by **owner** + resolve by hierarchy walk (converter §9).

18. **`ModelBlockRenderer.resetTintCache()` — private via `invokevirtual`.** A MixinExtras-wrapped body calls a private method via `invokevirtual` (nestmate style); the private-method reflection only handled `invokespecial`. **Fix:** handle `invokevirtual` too (converter §7).

19. **`KeyboardInput`/`Input.initial` — cross-target `@Unique` field.** Field added to base `Input`, accessed from `KeyboardInput`'s sidecar → `NoSuchFieldError`. **Fix:** **GFIELD** global table + hierarchy walk (converter §9).

20. **`ClientInput$$LBSidecar` — future sidecar not yet defined.** GFIELD correctly routed to `ClientInput`'s sidecar, but `ClientInput` is future-loaded so its sidecar wasn't defined yet under a concurrent tick → `NoClassDefFoundError`. **Fix:** **eager-define ALL** sidecars up front (future ones with a reference MC PD) (converter §"eager definition").

21. **`GuiAddition` cast inside a mixin body.** `Hud`'s own sidecar does `((GuiAddition)this)` where the interface was dropped → `ClassCastException`. `rewriteCaller` only covered LB's own code, not mixin bodies. **Fix:** **GIFACE** interface-cast rewrite inside mixin bodies (converter §8).

22. **`GuiMessage.addedTime` — record `NoSuchFieldError` (INTERMITTENT, NOT FULLY RESOLVED).** On world-join, LB's `ChatHudExtension.addMessage` builds a `GuiMessage` (a `record`); its canonical `<init>` sometimes fails with `NoSuchFieldError: int addedTime`. **The CFT logs that it defines `GuiMessage` *with* `addedTime`** (offline conversion is correct — record intact, 5 components — and the AW has no `GuiMessage` field directives, so `awApply` returns null/unchanged). Yet the loaded class sometimes lacks the field. It reproduced in several runs and *not* in others (a live-only, timing/threading-dependent effect I could not pin without a live class dump). **Open issue** — blocks world-entry when it fires; a run that reaches in-world proves it's not universal. First thing to characterize on resume for the in-world path: capture the *actually-loaded* `GuiMessage` bytes (retransform-capture) vs the CFT-returned bytes.

**Once past all of the above**, a run reaches: LB fully initialized, entered a "**LiquidBounce … Singleplayer**"-titled world, 4 render modules enabled, **no crash**, MC alive, render events firing ~30/sec. See `evidence/live-inworld.png`, `evidence/live-tracers.png`. **But the CEF UI still doesn't composite** → the final root cause.

---

## FINAL ROOT CAUSE — the CEF UI does not composite (the frontier)

The whole functional-render failure localizes to **one late-attach lifecycle coupling**, not the converter. Fully evidenced:

**Verified working (probed on a live, kept-alive instance):**
- CEF **paints a valid texture** — probed `browser.texture`: NON-NULL (`GlTextureView`, 1280×800, bgra), `isTextureReady=true`, `isUnpainted=false`.
- Render events fire — instrumented `EventManager.callEvent`: `ScreenRenderEvent`/`OverlayRenderEvent`/`GameRenderEvent` ~30/sec (6545 events).
- `BrowserRenderer.render()` executes — instrumented: ~123 calls / 4s.
- The JCEF `RenderPipeline` objects are **non-null** (probed the fields: `BGRA_BLURRED_TEXTURE = liquidbounce:pipeline/jcef/bgra_blurred_texture`).
- The **native theme aurora renders** through the **identical** draw path (`ThemeBackground.Image.draw → context.drawTexQuad → guiRenderState.addGuiElement`, with the reflected `guiRenderState`/`scissorStack`). So the reflected/converted draw path is fine.

**The only difference between the theme (renders) and the CEF quad (doesn't):** the theme uses vanilla shader `RenderPipelines.GUI_TEXTURED`; the CEF quad uses LB's custom pipeline `jcef/bgra_blurred_texture` with fragment shader **`liquidbounce:shader/fragment/bgra_pos_tex_color`**.

**The mechanism (log-evidenced):**
```
ERROR: Couldn't find source for FRAGMENT shader (liquidbounce:shader/fragment/bgra_pos_tex_color)
ERROR: Couldn't compile pipeline liquidbounce:pipeline/jcef/bgra_blurred_texture: fragment shader ... was invalid
```
- LB compiles its render pipelines via **`ClientRenderPipelines.precompile()`**, which passes LB's own shader source provider **`ClientShaders`** (`gpuDevice.precompilePipeline(pipeline, ClientShaders)`).
- `precompile()` is invoked **only** from `MixinShaderManager` — `@Inject(method="apply(Configs, ResourceManager, Profiler)", at=@At("TAIL"))` → i.e. it runs **inside MC's startup `ShaderManager.apply`**.
- On the **late-attach** path, `ShaderManager.apply` already ran at startup **before LB attached**, so `precompile()` never fired. The JCEF pipelines then **lazy-compile** later (when `BrowserRenderer` first draws) using MC's **default** shader source — which lacks LB's shaders → **"Couldn't find source"** → pipeline invalid → the CEF quad draws nothing every frame.
- The shader source **does exist** in the LB jar: `resources/liquidbounce/shaders/bgra_position_tex_color.frag`, served by LB's `ClientShaders` provider — it just isn't wired into the compile because the compile ran at the wrong time.
- `Reloading ResourceManager: vanilla` confirms LB's namespace isn't a registered resource pack either (LB uses its own `ClientShaders` provider, not MC's pack system).

**The decisive experiment (evidence it's a lifecycle/cache problem):** calling `ClientRenderPipelines.precompile()` **post-attach** moved the error from `Couldn't find source` (source now resolved via `ClientShaders`) to **`was invalid` / `Duplicate bind name 'Globals' in bind group layout`** — because the pipelines had already **cached a failed lazy-compile** and their bind groups were already registered; re-compiling collides with that state. The resulting frame was visually **identical to the theme-only title** (`evidence/live-1-title.png`) — no CEF foreground appeared, so no byte-distinct capture of it was preserved.

**Conclusion:** the CEF/custom-shader render layer is structurally coupled to MC's **startup `ShaderManager.apply`** lifecycle. The watcher/early-attach path composites the full UI *because it's present when `apply` runs*. The late-attach path misses that window and can't cleanly re-enter it. This is **not** a mixin-conversion defect — converted mixins, hooks, CEF paint, and the draw path all work.

---

## Candidate fixes to try on resume (both uncertain, both fight the late-attach lifecycle)

**(A) Register `ClientShaders` + reset the failed pipeline/bind-group cache before first draw.**
- Ensure `ClientShaders` is the shader source *before* the JCEF pipelines are first used, and **invalidate the cached failed compilations + registered bind groups** so `precompile()` re-compiles cleanly.
- Needs: access to the GPU device's pipeline/program **compilation cache** and bind-group registry (MC 26.2 `GpuDevice`/`RenderPipeline` compile cache internals — version-specific reflection). Remove the cached `bgra_*` compiled programs and the `Globals` bind-group-layout entries, then `precompile()`.
- Timing: ideally precompile **immediately after LB init, before `BrowserRenderer` first renders** (the JCEF pipelines are lazy-compiled on first draw). If you can run `precompile()` in that window (e.g. right after the `ClientStartEvent` kick, on the main thread, before returning to the render loop), the pipelines compile with `ClientShaders` and never cache a failure. This is the cleanest shot — try it first.

**(B) Force a clean full ShaderManager reload lifecycle post-attach.**
- Trigger `mc.reloadResourcePacks()` so `ShaderManager.apply` re-runs → `MixinShaderManager@TAIL → precompile()` runs at the *right* lifecycle point with fresh shader state (no cached failures).
- Blocker: `reloadResourcePacks()` **threw** (`InvocationTargetException`) on the late-attach state in testing — investigate/fix that first (it may need a specific screen/state, or it may trip one of the converted mixins mid-reload, like the world-load path did). A full reload also re-runs everything and may re-expose other late-attach issues.

**If both prove systemic** (the late-attach lifecycle genuinely can't deliver compiled render pipelines): report plainly and **bank the converter result** (full inject + init + hooks + CEF paint proven) — rely on the **watcher/early-attach path** for the CEF-composited UI. That path already renders the full working UI + real modules precisely because it runs the pipeline compilation at the correct startup lifecycle.

---

## Live-probe recipes (reuse these on resume)

All attach to a **persistent** MC (see `04-environment-gotchas.md` — do NOT use a one-shot harness that kills MC on exit) via `java -cp /tmp/attach-spike/out Injector <pid> <probe.jar> <args>`, running the work on the render/main thread via `Minecraft.execute(Runnable)`:
- **Is the browser painting?** `BrowserBackendManager.INSTANCE.backend.browsers` → per browser `getTexture()` (null if unpainted) + `browserApi.renderer.isTextureReady()/isUnpainted()`.
- **Do render events fire?** Retransform `net/ccbluex/liquidbounce/event/EventManager` and inject a branch-free `INVOKESTATIC` logging call at the head of `callEvent(Event)` (COMPUTE_MAXS is enough for a straight-line insert; branches would need COMPUTE_FRAMES).
- **Does `render()` run?** Retransform `BrowserRenderer` and inject a log at `render()`.
- **Pipeline validity / shader errors:** read `ClientRenderPipelines$JCEF` fields; grep the log for `Couldn't find source` / `was invalid` / `Duplicate bind name`.
- **Dump converted bytecode:** retransform a class with a transformer that writes the current bytes to `/tmp/dump-*.class`, then `javap -p -c` it (this is how the `drawTexQuad`-is-inlined + `AwReflect.gO("guiRenderState")` discovery was made).
