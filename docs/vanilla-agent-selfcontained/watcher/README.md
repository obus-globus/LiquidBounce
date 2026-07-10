# LiquidBounce watcher — no-flag injection into vanilla MC

> **CORRECTION:** the watcher works, but *not* by beating an "early SIGQUIT window" — a live MC is
> attachable at any point (see `../../vanilla-dynamic-attach-spike/BARRIER1-PROBE.md`; the earlier
> SIGQUIT-window claim was a `timeout`-wrapper measurement artifact). The watcher's real value is
> **coverage**: attaching early minimizes the already-loaded set, so more mixin targets are caught
> on-load rather than falling under the retransform schema-change wall.


Inject LB into **stock vanilla Minecraft started with no flags** — no `-javaagent`, no
`--add-opens`, no launcher. The user runs the **watcher** first; it detects a Minecraft
JVM and, in the early boot window, attaches the pure LB agent (`:vanillaPureAgentJar`) so
LB transforms MC as it loads — the dynamic-attach form of the pure agent.

```
./gradlew :vanillaWatcherJar :vanillaPureAgentJar [-PbundleMcefNative]
# terminal 1 — start the watcher FIRST:
java -jar liquidbounce-watcher.jar [liquidbounce-agent-vanilla-pure.jar]
# terminal 2 — launch stock vanilla MC with NO flags (the normal Mojang-launcher command)
```

Separate injector process — the normal, allowed attach model; `jdk.attach.allowAttachSelf`
is not needed.

## How it works

- **Watcher** (`src/LiquidBounceWatcher.java`): a warm in-process poller. It scans
  `VirtualMachine.list()` every 20 ms for a JVM whose display name is
  `net.minecraft.client.main.Main`, and the instant one appears attaches with a tight
  in-process retry loop (no per-attempt JVM spawn) to hit the **early SIGQUIT window** —
  before GLFW inits the window and drops SIGQUIT (which is what makes a *running* MC
  un-attachable; see `../../vanilla-dynamic-attach-spike/`).
- **Agent** (`PureVanillaAgent.agentmain` == `premain`): the exact pure-agent pipeline —
  append LB + deps to the system loader, standalone Mixin service + AW, transform MC as it
  loads, inject Mixin synthetics into the system loader. Two things make it work with **zero
  JVM flags** on MC:
  - it **opens `java.base/java.lang` to itself via `Instrumentation.redefineModule`** (so the
    reflective `defineClass`/`ProcessEnvironment` calls need no launch `--add-opens`), and
  - its transformer **only ever touches `net.minecraft.*`/`com.mojang.*`** — never JDK/system
    classes. When attached this early it would otherwise be invoked on `sun.security.*` mid
    signed-jar verification and throw `ClassCircularityError`, killing MC.
  It's idempotent (one arm; re-attach is ignored).

## Verified end-to-end (`watcher-e2e-evidence.log`)

Watcher running, then **stock vanilla MC launched with no flags**: watcher detects + attaches
at **~1.0–1.2 s** and arms the agent → `Launching LiquidBounce v0.38.1`, 39 render pipelines,
`CefBrowser(visible=true) is ready`, **0 synthetic CNF / signer / mixin-apply / circularity**.
LB's MCEF menu renders (`lb-mcef-splash-via-watcher.png` → `lb-mcef-menu-via-watcher.png`),
offline with `-PbundleMcefNative` (0 downloads). Window confirmed owned by the MC JVM.

**Reliability:** 6/6 runs hit the window on this VM (attach at +988/+1014/+1075/+1085/+1096/+1213 ms),
LB inited every time.

## Coverage gap (the viability-decider) — measured, not hand-waved

The attach lands at ~1.1 s; any LB mixin whose target class loads **before** that is missed
(the transformer isn't armed yet). Cross-referencing LB's **151** distinct `@Mixin` target
classes against a timestamped boot (`lb-mixin-targets.txt`):

- **3 of 151** targets load before the attach floor — all in `net.minecraft.network.chat`
  (`Style`, `MutableComponent`, `contents.TranslatableContents`, at ~655–756 ms during
  Bootstrap/registry init). The mixins on them — **`MixinStyleAccessor`,
  `MixinMutableComponent`, `MixinTranslatableContents`** (text/component features) — are the
  gap: only `-javaagent`/premain covers them.
- The other **148** targets load after ~2 s and are caught. LB fully initializes and MCEF
  renders regardless (those 3 are component/text mixins, not init-critical).

So the watcher gives **~98% mixin coverage** (148/151); a `-javaagent`/premain has no gap.

## Honest caveat — this VM's slow software-GL boot

This VM renders on software GL (llvmpipe), which makes boot **slow** and the early SIGQUIT
window **generous** (attach at ~1.1 s comfortably beats most class loads). On a fast real
machine the window is **narrower** — GLFW inits sooner, so the attach must land sooner, and
more early-loading targets could fall into the gap (or the window could be missed entirely on
a very fast boot). The 6/6 reliability and the 3-mixin gap here are **specific to this VM's
timing** and do not guarantee the same on faster hardware. A native `jattach` (lower attach
latency) would help but can't remove the floor. For guaranteed full coverage, the
`-javaagent`/premain path (`../PURE-AGENT.md`) has no window and no gap.
