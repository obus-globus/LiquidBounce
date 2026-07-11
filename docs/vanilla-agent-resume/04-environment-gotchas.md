# 04 — Environment + gotchas (read before resuming)

These will bite a cold resume. Every one was learned the hard way in this session.

---

## Repo / build coordinates
- **Branch:** `feat/vanilla-agent`. **Base commit at pause:** `f05ddf4c6` ("converter M1: real LB mixin set converts at scale").
- LB repo root: `/home/clawd/obus/liquidbounce-and-stuff/liquidbounce neoforge/LiquidBounce/` (note: the path has a space — quote it).
- **MC 26.2**, **JDK 25.0.3** (OpenJDK). Git committer identity `obus-globus` is already configured.
- Loom-remapped MC client jar: `~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar`.

## JDK-25 dynamic attach
- Dynamic attach (`VirtualMachine.attach(pid).loadAgent(jar)` from a **separate** process) **still works** on JDK 25. `jdk.attach.allowAttachSelf` is NOT needed (never self-attach; always use the separate `Injector` process). JDK prints a warning (`A Java agent has been loaded dynamically`) — harmless.
- **Barrier ① was a myth:** a live no-flag MC *does* catch SIGQUIT and *can* be attached at any time. The earlier "MC drops SIGQUIT / can't attach" was a **measurement artifact** — the pid used was the `timeout`-wrapper process, not the JVM. Verify with `VirtualMachine.list()` (only the real JVM shows). See `docs/vanilla-dynamic-attach-spike/BARRIER1-PROBE.md`.

## `--add-opens`
- The **premain** pure-agent needs `--add-opens java.base/java.lang=ALL-UNNAMED` (reflective `ClassLoader.defineClass`).
- The **dynamic-attach** `FullInjectAgent` does NOT need the flag — it self-opens via `inst.redefineModule(Object.class.getModule(), …, Map.of("java.lang", Set.of(agentModule)), …)`.

## Display / headless (Xvfb) — biggest time-sink of the session
- GUI runs on **Xvfb** virtual displays. **The shared display `:99` is contaminated** — other projects (and past runs) leave **leftover blocking native dialogs** (zenity "LiquidBounce Nextgen has encountered an error!") that linger and get captured in screenshots. **Use a fresh/dedicated display** (this work used `:121`). Kill stray dialogs: `pkill -9 -f 'zenity.*LiquidBounce'`.
- **Software GL only (no GPU / llvmpipe).** MCEF logs `No EGL context available for accelerated paint … Falling back to software rendering for browser`. Software CEF *does* paint a valid texture (proven), but shader/pipeline behavior may differ from GPU — keep this in mind when interpreting render results, though the shader-lifecycle root cause (`03`) is independent of software-GL.
- Capture the window **by ID** (`xdotool search --name 'iquidBounce'` → `import -window <id>`), not just root, to avoid stale/other-window frames. **Look at the pixels yourself** — the window title becomes `LiquidBounce v0.38.1 (dev) … | 26.2` (and `… - Singleplayer` in-world) once LB is injected, which is itself a signal.

## `CI=1` — stop blocking error dialogs
- LB's `ErrorHandler.fatal()` normally shows a **modal native dialog** (`TinyFileDialogs.tinyfd_messageBox`) then `exitProcess(1)`. In headless runs the modal **blocks forever** on input that never comes, and spawns the zombie zenity processes above. **Set `CI=1`** in MC's env → `buildAndShowMessage()` logs the error instead of showing a dialog (`!System.getenv("CI").isNullOrEmpty()`). Do this always for automated runs.

## Persistent MC — do NOT use one-shot harnesses
- A run script that backgrounds MC and then **exits** tends to take MC down with it (process-group teardown), so you **lose the live instance** you wanted to probe. **Launch MC detached and persistent** (`setsid nohup … & disown`), get the PID, and then attach/probe/enable/capture against the **same live instance** across many steps. This is essential for the iterative shader-lifecycle work.
- Use **unique per-run log files** (`LOGF=/tmp/fullmc-$(date +%s).log; ln -sf "$LOGF" /tmp/fullmc.log`) — reusing one log path caused repeated **stale-read races** (a poller reading the previous run's log before the new run truncated it). Key waits on a *fresh* marker (e.g. a `[FULL] phase A:` line with a recent timestamp), not on content that also existed in the prior run.

## Time / cache note (agent runtime, not LB)
- If scripting waits, be aware `Date.now()`/`Math.random()`/argless `new Date()` are unavailable in the workflow runtime — irrelevant to LB, but noted since some tooling uses it.

## Where the evidence lives
- **In-repo (preserved):** `docs/vanilla-agent-resume/evidence/`
  - `live-inworld.png`, `live-tracers.png` — full LB injected + running in a *"LiquidBounce … Singleplayer"* world (real in-world frame; modules enabled; no crash). **No visible ESP/tracer output** because `RenderedEntities` is empty in SP and the CEF/HUD foreground doesn't composite — see below.
  - `live-1-title.png` — the native theme **aurora backdrop** = LB rendering in-game, but **no CEF foreground** (the frontier).
  - (The post-hoc `precompile()` experiment produced a frame **visually identical** to `live-1-title.png` — still theme-only, bind-group cache collision — so no separate capture is kept.)
- **In-repo (source):** `docs/vanilla-agent-resume/converter-core/` — `FullInjectAgent.java`, `RetransformConverter.java`, `AwReflect.java`, `run-menu2world.sh`.
- **Ephemeral (VM `/tmp`, will be lost):** `/tmp/full/` (build dir + `full-agent.jar`, ~258 MB with offline MCEF), `/tmp/attach-spike/out/Injector.class`, `/tmp/vspike-run/` (bare MC classpath/assets/world), `/tmp/fullmc*.log`. Rebuild `full-agent.jar` from the `converter-core/` sources + the pure-agent jar (see below).
- **Older spike docs:** `docs/vanilla-dynamic-attach-spike/` (RESULTS.md, BARRIER1-PROBE.md, `barrier2-converter/` with RESIDUE-MAP.md, RESULTS.md, converter-core/, MILESTONE1-SCALE.md).

## Rebuilding `full-agent.jar` (the converter agent) from source
The agent reuses the pure-agent bundle (LB + deps + MCEF) and swaps in the converter classes + sets `Agent-Class: FullInjectAgent`:
```bash
PA=build/agent-vanilla/liquidbounce-agent-vanilla-pure.jar   # from vanillaPureAgentJar -PbundleMcefNative
MC=~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar
mkdir out
javac --release 25 -cp "$PA:$MC" -d out \
  docs/vanilla-agent-resume/converter-core/FullInjectAgent.java \
  docs/vanilla-agent-resume/converter-core/RetransformConverter.java \
  docs/vanilla-agent-resume/converter-core/AwReflect.java
cp "$PA" full-agent.jar
# set Agent-Class: FullInjectAgent in META-INF/MANIFEST.MF (extract, edit, jar ufm)
# add lb-mixin-targets.txt (the 146 target list) as a jar resource; add out/*.class + out/lbrt/*.class
( cd out && jar uf ../full-agent.jar . )
```
`lb-mixin-targets.txt` (the 146-target list, one internal/dotted class name per line) was at `/tmp/lb-mixin-targets.txt` — regenerate from `liquidbounce.mixins.json` + `liquidbounce-fabric.mixins.json` `@Mixin` targets if lost.

## Mental-model reset for a cold resume
1. Read `RESUME.md` → `01` (run the **watcher** to see the full working UI, and to have a reference for what "correct" looks like) → `02` (converter) → `03` (the frontier — start at "Candidate fixes").
2. Launch a **persistent** MC on a **fresh Xvfb** with `CI=1`, inject `full-agent.jar` at the **menu**, confirm LB inits + browser texture paints (probe recipes in `03`), then attack the shader-lifecycle: try candidate fix (A) first (precompile with `ClientShaders` before first draw + cache reset).
3. The bar: `import`-capture the window and **see LB's CEF UI composited** (menu/ClickGUI/HUD), not the aurora backdrop, not vanilla. Verify pixels yourself.
