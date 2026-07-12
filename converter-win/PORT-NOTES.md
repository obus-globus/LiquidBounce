# PORT-NOTES.md — Windows port of the vanilla-agent converter harness

Scope: analysis + portable assets so the baseline-repro step can build `full-agent.jar` and attach it to a **running
Windows Minecraft**. This does NOT build the jar (a parallel agent does). Read alongside
`docs/vanilla-agent-resume/RESUME.md` and `03-attach-frontier.md`.

Deliverables in this dir:
- `Injector.java` — the standalone dynamic-attach injector, rewritten from scratch (Windows compile/run lines inside).
- `run-menu.ps1` — PowerShell port of `run-menu2world.sh` (TODO placeholders for machine-specific paths).
- `lb-mixin-targets.txt` — first-cut 151-target list (regenerated; see `REGEN-TARGETS.md`).
- `REGEN-TARGETS.md` — exact regeneration procedure + hazards.
- `PORT-NOTES.md` — this file.

---

## 1. Linux/Unix-specific assumptions in the converter-core sources

Good news: **the converter engine is already portable.** `RetransformConverter.java` and `AwReflect.java` are pure ASM /
reflection — **zero** filesystem, shell, classpath-separator, or display assumptions. `FullInjectAgent.java` uses portable
APIs throughout (`Files.createTempDirectory`, `File`, `Instrumentation`) with exactly ONE hardcoded Linux path, and it's
debug-only. All the OS coupling lives in the **shell harness**, which is replaced by `run-menu.ps1`.

### Hard blockers (must change to run on Windows)

| # | File:line | Issue | Fix |
|---|-----------|-------|-----|
| B1 | `run-menu2world.sh` (entire file) | Bash script: `pkill`, `pgrep`, `/proc/$p/comm`, `paste -sd:`, `setsid`-style backgrounding, `import`(ImageMagick), `xdotool`, `env -u`, `DISPLAY=:121`/Xvfb. None exist on Windows. | Replaced wholesale by `converter-win/run-menu.ps1`. |
| B2 | `run-menu2world.sh:6` | `MC_CP=$(paste -sd: ...)` — classpath joined with **`:`** (Unix separator). On Windows the classpath separator is **`;`**. | `run-menu.ps1` joins with `';'`. Any regenerated `mc-cp.txt` consumer must use `;`. |
| B3 | `run-menu2world.sh:18,23,30` | Injector at `/tmp/attach-spike/out` (lost) invoked via `java -cp /tmp/...`. | `converter-win/Injector.java` + `-cp out` on a Windows path. |
| B4 | Environment (not in Java) | Xvfb/`DISPLAY`, SIGQUIT early-attach window, `zenity` dialogs, `XDG_RUNTIME_DIR`, `WAYLAND_DISPLAY` | **Do not apply on Windows** — real desktop, real window, no X server. SIGQUIT is a Unix signal; the JVM attach handshake on Windows uses a **named pipe**, not a signal file, but `VirtualMachine.attach(pid)` abstracts that — no code change. `CI=1` (LB's dialog suppressant) is honored on Windows too and is still worth setting for unattended runs. |

### Soft / cosmetic (works, but tidy)

| # | File:line | Issue | Note |
|---|-----------|-------|------|
| S1 | `FullInjectAgent.java:128` | `java.nio.file.Path.of("/tmp/guimsg-returned.class")` — hardcoded Linux `/tmp` debug dump (GuiMessage bug #22 diagnostic). | Wrapped in `try{}catch(Throwable){}` — on Windows it throws (invalid path) and is swallowed, so it's harmless, but the dump silently won't be written. If you need that diagnostic on Windows, change to `System.getProperty("java.io.tmpdir")` or a configurable path. Not a blocker. |
| S2 | `FullInjectAgent.java:46` | `Files.createTempDirectory("lb-full-")` | Portable — resolves to `%TEMP%` on Windows. No change. |
| S3 | `FullInjectAgent.java:50` | `inst.appendToSystemClassLoaderSearch(new JarFile(...))` staging agent-libs from a temp dir | Portable. No change. |

**Confirmed absent** (searched all four sources): no `File.pathSeparator` misuse, no hardcoded `':'` classpath in Java,
no `ProcessBuilder`/`Runtime.exec`/`sh`/`bash`, no forward-slash path string-building that would break, no `DISPLAY`
reads. The single Linux path is S1 (debug-only). Everything else that is Linux-shaped is the shell harness → `run-menu.ps1`.

---

## 2. Harness port status (`run-menu.ps1`)

Faithful logic port of `run-menu2world.sh`. Key mappings:
- **Detached + persistent MC**: `Start-Process -FilePath java -PassThru` → exact PID from `$mc.Id`. This is *better* than
  the Linux `pgrep + /proc/comm` heuristic (which existed only because bash backgrounding loses the real JVM PID behind
  wrappers). The script **never** kills MC on exit — matches the "persistent MC" rule (`04-environment-gotchas.md`).
- **Classpath**: joined with `';'` (see B2).
- **Menu-first**: boots to the menu (no quickplay) so LB inits calmly, then optionally attaches `enterworld.jar` then
  `opengui.jar` — the deliberate ordering from fix #10 (`03-attach-frontier.md`).
- **`CI=1`**: `$env:CI = '1'` before launch.
- **Log polling**: `Wait-ForLog` replaces `grep -q` loops; uses a per-run timestamped log (avoids the stale-read race
  from `04`).
- **TODO placeholders** (clearly marked in the file): `$JavaExe` (JDK 25), `$McCpFile` (mc-cp.txt), `$SeedWorld`,
  `$FullAgentJar`/`$EnterWorldJar`/`$OpenGuiJar`, `$InjectorOut`. These are the machine-specific bits the baseline-repro
  step fills in.

Not ported: screenshot capture (`import`/`xdotool`). On Windows, capture the LB window by handle — see below.

---

## 3. Staging a bare-vanilla MC 26.x instance on Windows

### 3a. Where the MC jar + assets live (verified on this machine, user `Raphael`)
- Loom cache root: `C:\Users\Raphael\.gradle\caches\fabric-loom\`
- Remapped client jars present: **`26.1.2\minecraft-client.jar`** and **`26.2\minecraft-client.jar`** (both exist).
- Shared assets: `...\fabric-loom\assets\` with `indexes\` + `objects\` (standard Mojang asset store).
- Asset indexes cached: `26.1.2-30.json`, `1.21.11-29.json`, `1.21.4-19.json`.
  - **26.1.2 → assetIndex `30`** (fully cached locally). ← default in `run-menu.ps1`.
  - **26.2 → its asset index is NOT in `indexes\`** yet; a 26.2 run must fetch it (or reuse 30 if compatible — verify).
  - (The Linux 26.2 harness used `--assetIndex 32`; on this machine use `30` for 26.1.2.)

### 3b. The one missing piece — the full runtime classpath
The loom cache gives the *client jar*, not the full library classpath (LWJGL, gson, guava, netty, authlib, slf4j, …).
The baseline-repro step must assemble `converter-win\mc-cp.txt` (one absolute jar path per line). Options:
- **Preferred**: add a tiny Gradle task (or reuse an existing run config) that prints the `minecraftRuntime`/runtime
  classpath, redirect to `mc-cp.txt`. The libraries are already downloaded under the Gradle caches (loom pulls them).
- **Alternative**: reuse a real launcher (official launcher / Prism) instance of the same version and point the classpath
  at its `libraries\` + the loom client jar.
`run-menu.ps1` joins the file with `;`. Without it, MC will not boot (client jar alone lacks LWJGL etc.).

### 3c. Game dir + test world
- `run-menu.ps1` builds `%TEMP%\fullmc\gamedir` with a minimal `options.txt` (narrator off, low render distance,
  `pauseOnLostFocus:false`).
- A **test world** is needed only for the enter-world / in-world steps (the menu attach works without one). Set
  `$SeedWorld` to an existing `saves\<World>` folder (copy from any singleplayer instance), or create one once with a
  normal MC run and reuse it.

---

## 4. JDK situation — **the biggest open blocker**

The toolchain requires **JDK 25** (`gradle.properties` mixins use `compatibilityLevel: JAVA_25`; docs pin JDK 25.0.3;
`Injector.java`/agent target release 25). **No JDK 25 (or 21/24) is installed on this machine.** Present JDKs:
`C:\Program Files\Java\jdk1.8.0_*`, `C:\Program Files\Eclipse Adoptium\jdk-8/17/18`, and a bundled JetBrains
`jbr_jcef-17`. Nothing ≥ 21.

**Action for baseline-repro (do first):** install a JDK 25 (e.g. Adoptium/Oracle 25), set `JAVA25_HOME`, and point
`run-menu.ps1 $JavaExe` + the agent build + `javac`/`java` for `Injector` at it. Dynamic attach on JDK 25 works (prints a
harmless "agent loaded dynamically" warning). This blocks BOTH this harness and the parallel `full-agent.jar` build.

---

## 5. Open questions the baseline-repro step must resolve

1. **JDK 25** — install one (blocks everything). See §4.
2. **MC version: 26.1.2 vs 26.2.** Both client jars are cached. The mixin set / target list was generated against the
   **26.1.2** sources checked out here (`mod_mc_version=>26.1.2 <26.3`). The docs/VM used 26.2. Decide which to run;
   26.1.2 has a locally-cached asset index (30), 26.2 does not. The `lb-mixin-targets.txt` matches the current checkout.
3. **`mc-cp.txt`** — assemble the full runtime classpath (§3b). Biggest concrete gap.
4. **`full-agent.jar`** — produced by the parallel build agent; drop it where `run-menu.ps1 $FullAgentJar` points. It must
   embed `lb-mixin-targets.txt` (this dir), the converter classes (`FullInjectAgent`/`RetransformConverter`/`AwReflect`),
   and set `Agent-Class: FullInjectAgent` (see `04-environment-gotchas.md` "Rebuilding full-agent.jar").
5. **enterworld.jar / opengui.jar** — the Linux harness attached these (sources not in `converter-core/`; they're small
   attach agents: `WorldOpenFlows.openWorld` and module-enable). If needed on Windows they must be rebuilt too; the
   menu-only baseline does not require them.
6. **Screenshot on Windows** — replace `import -window`. Capture the LB window by handle: find the MC window (title
   becomes `LiquidBounce v0.38.1 (dev) ... | 26.x`, and `... - Singleplayer` in-world), then use `Add-Type`
   `System.Drawing`/`Graphics.CopyFromScreen` over the window's `RECT` (P/Invoke `GetWindowRect`), or a tool like
   `nircmd savescreenshot`. Not needed to prove init (the log's "successfully initialized" is enough); needed to prove the
   CEF-UI composite frontier.
7. **GPU vs software GL.** The Linux runs were software-GL (llvmpipe under Xvfb). Windows will use the real GPU. The
   shader-lifecycle root cause (`03`) is GL-independent, but late-attach render behavior may differ on real hardware —
   worth noting when interpreting the CEF-composite result.
