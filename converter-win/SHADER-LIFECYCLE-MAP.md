# SHADER-LIFECYCLE-MAP — LB custom render-pipeline / shader compile lifecycle (MC 26.2)

Read-only analysis to prepare the late-attach fix for the CEF-UI-not-compositing bug
(root cause documented in `docs\vanilla-agent-resume\03-attach-frontier.md` §"FINAL ROOT CAUSE").
All MC bytecode facts below were verified by disassembling the mapped jar:
`C:\Users\Raphael\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`.

---

## 1. `ClientRenderPipelines` — LB's pipeline definitions

File: `src\main\kotlin\net\ccbluex\liquidbounce\render\ClientRenderPipelines.kt`

- **Registry map** (line 44): `private val renderPipelines = Object2ObjectOpenHashMap<Identifier, RenderPipeline>()`
- **Builder factory** (lines 51–62): `newPipeline(name) { ... }` — `RenderPipeline.Builder()` (private ctor, opened by AW line 148) → `.withLocation(LiquidBounce.identifier("pipeline/$name"))` → `.build()` → put into `renderPipelines`.
- **All pipelines are eagerly built as static (`object` clinit) fields** — `RenderPipeline` objects are constructed the moment `ClientRenderPipelines` / its nested `JCEF` / `GUI` objects are class-initialized. Building a `RenderPipeline` does NOT compile it — compilation is a separate GPU-device step (see §5).
- **`precompile()`** (lines 465–473):
  ```kotlin
  fun precompile() {
      JCEF
      GUI
      renderPipelines.fastIterator().forEach { (_, pipeline) ->
          gpuDevice.precompilePipeline(pipeline, ClientShaders)
      }
      logger.info("Loaded ${renderPipelines.size} Render Pipelines.")
  }
  ```
  Touching `JCEF`/`GUI` forces their clinit (registers their pipelines into the map), then EVERY LB pipeline is compiled with LB's `ClientShaders` source. `gpuDevice` = `RenderSystem.getDevice()` (`src\main\kotlin\net\ccbluex\liquidbounce\utils\client\MinecraftExtensions.kt:53-54`).
- **JCEF pipelines** (`object JCEF`, lines 110–153):
  - `SMOOTH_TEXTURE` = `jcef/smooth_texture` (line 112) — vanilla `GUI_TEXTURED_SNIPPET`, no LB shader.
  - `BLURRED_TEXTURE` = `jcef/blurred_texture` (line 119) — vanilla snippet, no LB shader.
  - `BGRA_TEXTURE` = `jcef/bgra_texture` (line 125) — `bgraPosTexColorQuads()`.
  - **`BGRA_BLURRED_TEXTURE` = `jcef/bgra_blurred_texture` (lines 130–134)** — `bgraPosTexColorQuads()` + `JCEF_COMPATIBLE_BLEND`. This is the pipeline that fails on late attach.
  - `Blit` = `jcef_blit` (line 140) — vanilla `core/blit_screen` fragment.
- **`bgraPosTexColorQuads()`** (lines 64–71): vertex shader `"core/position_tex_color"` (VANILLA id, resolved from MC's shader set) + **fragment shader `ClientShaders.Fragment.BgraPosTex`** (LB id `liquidbounce:shader/fragment/bgra_pos_tex_color`) + `BindGroupLayouts.MATRICES_PROJECTION` + `SAMPLER0` + `POSITION_TEX_COLOR` quads. Note: LB pipelines mix vanilla shader ids and LB shader ids in the same pipeline — this matters for any cache-reset fix (§7).
- Other LB-shader pipelines: `GUI.CircleLut/RoundedRect` (162–177), `OutlineQuads`/`OutlineQuadsNoColor` (305–326 — see §5, these are the source of the "Duplicate bind name 'Globals'" message), `Outline` (408), `ItemChams` (423), `GuiBlur` (436), `Blend` (450), relative-to-camera lines/quads (232–299).

## 2. `ClientShaders` — LB's shader source provider

File: `src\main\kotlin\net\ccbluex\liquidbounce\render\ClientShaders.kt`

- `sealed class ClientShaders(val type: ShaderType) : ShaderSource` (line 28) — implements **`com.mojang.blaze3d.shaders.ShaderSource`** (functional interface: `String? get(Identifier, ShaderType)`).
- `newShader(id, path)` (lines 105–112): eagerly reads the GLSL text at clinit via `LiquidBounce.resourceToString(path)` (classpath resource with prefix `/resources/liquidbounce/`, `LiquidBounce.kt:176-188`) into `shaders: Object2ObjectOpenHashMap<Identifier, String>`, key = `liquidbounce:shader/<vertex|fragment>/<id>`.
- `Fragment.BgraPosTex` (line 68): `"bgra_pos_tex_color"("shaders/bgra_position_tex_color.frag")` → id `liquidbounce:shader/fragment/bgra_pos_tex_color`, source file `src\main\resources\resources\liquidbounce\shaders\bgra_position_tex_color.frag`.
- `companion object : ShaderSource` (lines 119–124) dispatches to `Vertex`/`Fragment` — this is the object passed to `precompilePipeline`.
- **`ClientShaders` returns `null` for anything not in its own map — including vanilla `core/*` ids.** It is NEVER registered globally with MC (not a resource pack, not the device default source); it only takes effect for the single `precompilePipeline(pipeline, ClientShaders)` call. MC's normal way to "know" a shader is the resource-pack `ShaderManager` prepare/apply cycle; LB deliberately bypasses that.

## 3. `MixinShaderManager` — the only trigger for `precompile()`

File: `src\main\java\net\ccbluex\liquidbounce\injection\mixins\minecraft\client\renderer\MixinShaderManager.java`

```java
@Mixin(ShaderManager.class)                                            // line 29
@Inject(method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("TAIL"))                                              // line 32
private void reloadClientPipelines(CallbackInfo info) {
    ClientRenderPipelines.INSTANCE.precompile();                       // line 34
}
```

Target: `protected void ShaderManager.apply(ShaderManager$Configs, ResourceManager, ProfilerFiller)` — the resource-reload apply phase. Verified `apply` body (bytecode): builds `ShaderManager$CompilationCache` from the prepared configs → **`GpuDevice.clearPipelineCache()`** → `precompilePipeline(p, compilationCache::getShaderSource)` for every pipeline in `RenderPipelines.getStaticPipelines()` (vanilla statics only; LB pipelines are NOT in that list) → if any invalid: `clearPipelineCache()` + `loadCriticalShaders()` + throw. LB's TAIL injection then compiles LB's pipelines. This ordering is why LB pipelines can reference vanilla `core/*` vertex shaders: by TAIL time, those shader modules are already in the device `shaderCache` (same `ShaderDefines`), so `getOrCompileShader` cache-hits and never consults `ClientShaders` for them.

**Late-attach failure:** `apply` runs once at startup (and on manual resource reload) — before LB attaches. `precompile()` therefore never runs; the mixin is correctly converted/installed but its trigger already fired.

## 4. `BrowserRenderer` — the lazy-compile trigger (first draw)

File: `src\main\kotlin\net\ccbluex\liquidbounce\integration\backend\browser\BrowserRenderer.kt`

- One instance per CEF browser: `CefBrowser.kt:147 private val renderer = BrowserRenderer(this)`.
- Draw entry points: `overlayRenderHandler` (`OverlayRenderEvent`, lines 64–75) and `screenRenderHandler` (`ScreenRenderEvent`, lines 78–84) → both call `render(context)` (lines 96–108) → `renderTexture(...)` (lines 111–130):
  ```kotlin
  val pipeline = if (texture.bgra) ClientRenderPipelines.JCEF.BGRA_BLURRED_TEXTURE   // line 119-123
                 else               ClientRenderPipelines.JCEF.BLURRED_TEXTURE
  context.drawTexQuad(texture.textureSetup, ..., pipeline = pipeline)                 // line 125-129
  ```
- `drawTexQuad` submits a GUI element with that pipeline; the actual GPU compile happens later inside MC's renderer when the pass binds the pipeline: `GlRenderPass`/`GlCommandEncoder` → `GlDevice.getOrCompilePipeline(pipeline)` → `pipelineCache.computeIfAbsent(pipeline, p -> compilePipeline(p, defaultShaderSource))` — i.e. the lazy path uses the DEVICE DEFAULT source, never `ClientShaders`.
- Guard host: `render()` (line 96) or `renderTexture()` (line 111) run on the render thread every frame and are the natural place for a "compile-if-not-yet-compiled" hook. There is currently NO such guard — the code assumes precompile already ran.
- Note the existing `resourceReloadHandler` (lines 88–91) + `forceReload` on next overlay render — LB already reacts to resource reloads here, but only to reload the browser page, not the pipelines.

## 5. MC 26.2 GPU compile API surface (verified from mapped bytecode)

### `com.mojang.blaze3d.systems.GpuDevice` (public facade)
- `CompiledRenderPipeline precompilePipeline(RenderPipeline)` / `precompilePipeline(RenderPipeline, ShaderSource)` — what LB calls.
- **`void clearPipelineCache()`** — PUBLIC. Closes all non-invalid programs/shader modules and clears both caches (also re-runs the AMD workaround).
- `void loadCriticalShaders()` — runs the `criticalShaderLoader` Runnable given at device construction.
- `CompiledRenderPipeline.isValid()` — lets a caller detect a poisoned/failed compile.

### `com.mojang.blaze3d.opengl.GlDevice` (backend, `implements GpuDeviceBackend`)
- `private final ShaderSource defaultShaderSource` — set once in the ctor; used for all lazy compiles. LB's shaders are invisible to it.
- **`pipelineCache: Map<RenderPipeline, GlRenderPipeline>`** — keyed by the `RenderPipeline` OBJECT. `RenderPipeline` does NOT override `equals`/`hashCode` (verified) → **identity-keyed**. Plain `HashMap` + `computeIfAbsent` → render-thread only, and a cached entry (valid or INVALID) is PERMANENT for that object; a later `precompilePipeline` with a different source is a **silent no-op**.
- **`shaderCache: Map<ShaderCompilationKey, GlShaderModule>`** — `ShaderCompilationKey` is a **record `(Identifier id, ShaderType type, ShaderDefines defines)`** → **value-keyed**. Failed lookups cache `GlShaderModule.INVALID_SHADER` under that key forever (until `clearPipelineCache`).
- `getOrCompilePipeline(pipeline)` → `computeIfAbsent(pipeline, compilePipeline(p, defaultShaderSource))` — the lazy path.
- `precompilePipeline(pipeline, source)` → `computeIfAbsent(pipeline, compilePipeline(p, source ?: defaultShaderSource))`.
- `compileShader(key, source)`: `source.get(id, type)` returns null → logs `"Couldn't find source for {} shader ({})"` → returns (and caches) `INVALID_SHADER`. **No fallback to the default source.**
- `compileProgram(pipeline, source)`: vertex/fragment via `getOrCompileShader` (cache-first!); INVALID module → logs `"Couldn't compile pipeline {}: ... was invalid"` → `INVALID_PROGRAM`; else `GlProgram.link(...)` + `program.setupBindGroupLayouts(pipeline.getBindGroupLayouts())`; catches `IllegalArgumentException`/`CompilationException` → logs `"Couldn't compile program for pipeline {}: {}"` → `INVALID_PROGRAM`. The `GlRenderPipeline(pipeline, INVALID_PROGRAM)` is then cached in `pipelineCache`.

### The "Globals" bind name — NOT a global registry
- `"Globals"` is a uniform-buffer entry in the static layout **`net.minecraft.client.renderer.BindGroupLayouts.GLOBALS`** (verified in `BindGroupLayouts.<clinit>`: `builder().withUniform("Globals", UNIFORM_BUFFER).build()`).
- `"Duplicate bind name '{}' in bind group layout"` is thrown by **`BindGroupLayout.ensureCompatible(List<BindGroupLayout>)`** — a STATELESS per-call check over one pipeline's OWN layout list (fresh local `HashSet` per invocation), called from `GlProgram.setupBindGroupLayouts` during `compileProgram`. **There is no device-global bind-group registry; nothing is "registered" that a re-compile could collide with.**
- Why the message appeared in the live experiment: `RenderPipelines.DEBUG_FILLED_SNIPPET` is itself built FROM `GLOBALS_SNIPPET` (verified in `RenderPipelines.<clinit>`: `RenderPipeline.builder(GLOBALS_SNIPPET) ... buildSnippet() → DEBUG_FILLED_SNIPPET`), and `Builder.withSnippet` merges bind-group layouts with a plain `addAll` (no dedup). LB's **`OutlineQuads` / `OutlineQuadsNoColor`** (`ClientRenderPipelines.kt:305-326`) apply `DEBUG_FILLED_SNIPPET` AND `GLOBALS_SNIPPET` → their layout list contains `GLOBALS` twice → `ensureCompatible` throws on EVERY compile of those two pipelines, on any lifecycle path (this should also appear in a normal-startup log — it is a latent LB bug, unrelated to late attach and unrelated to the JCEF pipeline). The doc's inference "bind groups were already registered; re-compiling collides with that state" is therefore wrong; the correct model is the two `computeIfAbsent` caches above.
- Consistent re-reading of the decisive experiment: post-attach `precompile()` was the FIRST full compile pass. Pipelines already lazily compiled (the drawn `BGRA_BLURRED_TEXTURE`) cache-hit as INVALID and were silently skipped; sibling pipelines sharing the poisoned fragment key (e.g. `BGRA_TEXTURE`, same id+defines) newly logged `"... was invalid"` without a fresh "Couldn't find source"; `outline_quads*` newly logged the Globals duplicate. Net effect: no error mentioned the source anymore, but the JCEF pipeline was never actually recompiled.
- Uniform binding at draw time (`Globals` value, not layout): `RenderPassExtensions.kt:65-66` `pass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform())` — irrelevant to compilation.

### Related access-widener entries (`src\main\resources\liquidbounce.accesswidener`)
- 148: `RenderPipeline$Builder <init> ()V`, 149: `RenderPipeline$Builder withSnippet(Snippet)V`
- 172–177: `RenderPipelines` snippet fields incl. `DEBUG_FILLED_SNIPPET` (173) and `GLOBALS_SNIPPET` (177).
- `Minecraft.getShaderManager()` is public in 26.2; `ShaderManager.getShader(Identifier, ShaderType)` is public and reads the retained `compilationCache` field — a usable vanilla-source fallback for LB code.

## 6. Physical shader source files

Under `src\main\resources\resources\liquidbounce\shaders\` (jar path `/resources/liquidbounce/shaders/...`, loaded via `LiquidBounce.resourceToString`, NOT via MC's resource-pack system):

```
bgra_position_tex_color.frag        <- Fragment.BgraPosTex = liquidbounce:shader/fragment/bgra_pos_tex_color  (CONFIRMED)
blend.frag  blit.frag  blur\ui_blur.frag
circle\{circle.vsh, gradient_circle.vsh/.fsh, gui_circle_lut.vsh/.fsh, rounded_rect.fsh}
glow\glow.frag   gui\rounded_rect.vsh/.fsh   heart\heart.fsh
outline\entity_outline.frag   plane_projection.vert   position_tex.vert
relative_to_camera\{position.vsh/.fsh, position_color.vsh}   sobel.vert
```

## 7. Fix-surface assessment (recommendation — not implemented)

### (B) answered first — what kind of state is the failure?
- **Pipeline compile cache: per-`RenderPipeline`-OBJECT** (`GlDevice.pipelineCache`, identity-keyed since `RenderPipeline` has no `equals`). Fresh pipeline objects DO sidestep it.
- **Shader compile cache: value-keyed** (`ShaderCompilationKey(id, type, defines)` record). A fresh pipeline object referencing the same `liquidbounce:shader/fragment/bgra_pos_tex_color` with the same (empty) defines **still hits the cached `INVALID_SHADER`** → fresh objects alone are NOT sufficient once a failed lazy compile happened. (Agent-side sidestep if ever needed: rebuild with a dummy `withShaderDefine("LB_LATE_ATTACH")` to change the key; but the LB-source fix below makes that unnecessary.)
- **"Globals" is NOT global state** — no cache reset can or needs to fix it; it is the `OutlineQuads*` double-`GLOBALS`-layout bug (see §5), which affects only those two pipelines and should be fixed separately by dropping the redundant `withSnippet(RenderPipelines.GLOBALS_SNIPPET)` at `ClientRenderPipelines.kt:307` and `:318` (DEBUG_FILLED_SNIPPET already carries it).
- Live probe should confirm: `GlDevice.pipelineCache.get(BGRA_BLURRED_TEXTURE).program() == GlProgram.INVALID_PROGRAM` and `shaderCache` containing key `(liquidbounce:shader/fragment/bgra_pos_tex_color, FRAGMENT, EMPTY)` → `INVALID_SHADER` (fields `pipelineCache`/`shaderCache` on `com.mojang.blaze3d.opengl.GlDevice`, reachable via `RenderSystem.getDevice()` → `GpuDevice.backend`).

### (A) the recommended LB-source change: first-use ensure + self-healing recompile
Cleanest shape — two small additions, no MC internals reflection needed:

1. **`ClientRenderPipelines.kt` — add an idempotent `ensureCompiled()` and a compiled-state flag:**
   - `precompile()` sets `@Volatile private var precompiled = true` at the end (line ~472).
   - New `fun ensureCompiled()` (render thread only):
     - fast path: `if (precompiled) return`.
     - Check poisoning: `val probe = gpuDevice.precompilePipeline(JCEF.BGRA_BLURRED_TEXTURE, ClientShaders)`; on a clean late attach (nothing drew yet) this alone compiles it correctly with `ClientShaders` — `computeIfAbsent` caches the GOOD result and the later lazy `getOrCompilePipeline` returns it untouched. Then run full `precompile()`.
     - If `!probe.isValid` (a failed lazy compile already got cached — the race lost): call **`gpuDevice.clearPipelineCache()`** (public API; drops both poisoned caches; vanilla pipelines lazily recompile from `defaultShaderSource`, which is exactly what MC's own `apply` failure path does), then `precompile()`.
   - **Required companion change for the reset path to work:** after `clearPipelineCache()` the vanilla `core/*` shader modules are gone from `shaderCache`, and `ClientShaders.get("core/position_tex_color", VERTEX)` returns null → LB pipelines with vanilla vertex shaders would poison the cache again. Fix by compiling with a fallback source instead of bare `ClientShaders`, e.g. in `precompile()` (line 470):
     `val source = ShaderSource { id, type -> ClientShaders[id, type] ?: mc.shaderManager.getShader(id, type) }` — `Minecraft.getShaderManager()` and `ShaderManager.getShader(Identifier, ShaderType)` are public in 26.2 and read the retained post-reload `compilationCache`. This also makes normal-startup `precompile()` strictly more robust (it currently relies implicitly on cache-hits from vanilla's earlier precompile pass with identical `ShaderDefines`).
2. **`BrowserRenderer.kt` — call the guard at first use:** first line of `render(context)` (line 96) or `renderTexture(...)` (line 111): `ClientRenderPipelines.ensureCompiled()`. Both handlers already run on the render thread (`OverlayRenderEvent`/`ScreenRenderEvent` fire during GUI rendering), which is mandatory: `pipelineCache`/`shaderCache` are plain `HashMap`s and compiles issue GL calls. After the first frame the guard is a single volatile read.
   - Optionally also call `ensureCompiled()` from the late-attach bootstrap right after the manual `ClientStartEvent` kick (already executed via `Minecraft.execute` → main/render thread) — that wins the race BEFORE any browser draw so the reset path is never even needed; the `BrowserRenderer` guard then remains as a belt-and-braces for other custom-pipeline users (`GuiBlur`, `Outline`, fonts, ESP renderers all share the same `renderPipelines` map and get compiled by the same `precompile()` call).
3. **Separate one-line latent-bug fix:** remove `withSnippet(RenderPipelines.GLOBALS_SNIPPET)` from `OutlineQuads` (`ClientRenderPipelines.kt:307`) and `OutlineQuadsNoColor` (`:318`) — otherwise those two pipelines keep failing `ensureCompatible` on every path, and their "Duplicate bind name 'Globals'" log line keeps masquerading as a lifecycle error.

Why (A) beats a forced resource reload (doc's candidate B): it needs no `reloadResourcePacks()` (which threw on the late-attach state), no version-specific reflection, is a no-op on the normal startup path (`precompiled` already true / caches already valid), and converts the compile lifecycle from "startup-window-only" to "first-use, correct-source, idempotent".

Thread-safety summary: everything (`ensureCompiled`, `precompile`, `clearPipelineCache`, any probe) MUST run on the render/main thread (`RenderSystem.assertOnRenderThread` semantics; the caches are unsynchronized `HashMap`s). `BrowserRenderer` handlers and `Minecraft.execute` both satisfy this.
