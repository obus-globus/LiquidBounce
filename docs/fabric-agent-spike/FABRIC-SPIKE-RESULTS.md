# Fabric agent spike — RESULT: GREEN

**Gate assertion (from the review): can a premain agent register one mixin config LATE
on a LIVE Fabric install and have it apply, without conflicting with fabric-loader's own
Mixin?** Answer: **yes.** Proven end-to-end.

## What ran

A `-javaagent` (LB **not** installed as a mod; `spike.mixins.json` is in **no**
`fabric.mod.json`) attached to Fabric's `runClient` (MC 26.2, Knot). The agent:
1. `premain` — installs an `Instrumentation` `ClassFileTransformer` (the verified non-mod
   hook), no standalone Mixin service.
2. When `net.fabricmc.loader.impl.launch.FabricMixinBootstrap` loads, **bytecode-rewrites
   its `init` method** to call `SpikeHook.onFabricMixinReady()` at the return site.
3. `SpikeHook` (runs right after Fabric sets up Mixin + adds its own configs):
   - `FabricLauncher.getLauncher().addToClassPath(agentJar, "spike")` — puts the spike
     classes onto **KnotClassLoader** (solves the classloader-identity trap);
   - `Mixins.addConfiguration("spike.mixins.json")` into the **live** Mixin.
4. `spike.mixins.SpikeMixin` `@Inject`s `Minecraft.getWindow` HEAD → println.

## Proof (`spike-proof.log`)

```
[FSPIKE] premain: installing FabricMixinBootstrap hook (non-mod)
[FSPIKE] rewrote FabricMixinBootstrap.init (1 return site(s) hooked)
[FSPIKE] added agent jar to Knot classpath, target CL=KnotClassLoader@...
[FSPIKE] addConfiguration(spike.mixins.json) OK; live service=MixinServiceKnot
[FSPIKE] >>> AGENT-ADDED MIXIN FIRED inside net.minecraft.client.Minecraft on a LIVE FABRIC install <<<   (x32990)
```

Every predicted mechanic held: **DEFER works** (config went into Fabric's own
`MixinServiceKnot` — no competing-service crash like vanilla premain), the **timing window
is real** (late `addConfiguration` applied), the **non-mod hook works** (Instrumentation
rewrite of `FabricMixinBootstrap`), and **classloader identity is solved** via
`addToClassPath`. One fix vs the first attempt: **do NOT bundle ASM in the agent jar** —
Fabric aborts on duplicate ASM on the classpath (`LoaderUtil.verifyClasspath`); use the
classpath's ASM.

## Implication

The gate is green → building the full LB-on-Fabric-via-agent interposer is warranted
(ClassTweaker for LB's AW, `addToClassPath` for LB's jar + kotlin-stdlib + FLK, the same
`FabricMixinBootstrap` hook to add `liquidbounce.mixins.json`).

---

# Full LB-on-Fabric-via-agent — DONE (milestones b + c)

Built the full interposer (`lb-agent/src/lbagent/{LBAgent,LBHook}.java`). Same non-mod
`FabricMixinBootstrap.init` hook as the spike, but the hook now injects **the real LB**:
- `addToClassPath(lb.classesKotlin/lb.classesJava, "net.ccbluex")` + `addToClassPath(lb.resources)`;
- LB's AccessWidener applied to the **live** Knot via the ClassTweaker system
  (`ClassTweakerReader.create(FabricLoaderImpl.INSTANCE.getClassTweaker()).read(aw, "official")`
  — the 0.19.3 replacement for the removed `FabricLauncherBase.AccessWidener`);
- `Mixins.addConfiguration("liquidbounce.mixins.json")` + `"liquidbounce-fabric.mixins.json"`.

**(b) LB initializes on Fabric-via-agent** — `lb-fabric-init-proof.log`,
`lb-full-menu-on-fabric-via-agent.png` (MCEF ClickGUI menu). LB is loaded by the agent,
**not** as a mod: it is absent from Fabric's 116-mod enumeration.

**(c) A module functions in-world** — Fly toggled via config, F3 shows the player rise
**Y=-60.0 → Y=-8.68 (+51 blocks)** in a superflat survival world.
Before: `lb-fabric-fly-before-y-60.png`; after: `lb-fabric-fly-after-y-9.png`.

## Clean-room reproduction (committed, not a hand-assembled fluke)

`fabric-agent-run.sh` reproduces the whole thing from committed artifacts + loom's own
outputs — no hand-edited argFiles:
1. Compiles the committed agent sources → `lbagent.jar` (asserts **0 bundled ASM**).
2. `./gradlew classes processResources` (LB build outputs the agent will inject).
3. Generates a Gradle **init script** that, on loom's `runClient` JavaExec,
   (a) filters LB's three build dirs (`build/classes/{java,kotlin}/main`, `build/resources/main`)
   **out of the mod classpath** so Fabric does not discover LB as a mod, and
   (b) attaches `-javaagent:lbagent.jar` + the `lb.classes*/lb.resources` locators.
4. `./gradlew :runClient --init-script …`.

Cold-run proof — `lb-fabric-cleanroom-proof.log`:
```
[fabric-agent] built lbagent.jar (bundled ASM: 0 …)
[LB-AGENT-INIT] LB removed from mod classpath; agent attached to runClient
[LBAGENT] rewrote FabricMixinBootstrap.init
[LBAGENT] LB classes+resources added to Knot=…KnotClassLoader@…
[LBAGENT] applied liquidbounce.accesswidener via ClassTweaker (13747 bytes)
[LBAGENT] added liquidbounce.mixins.json + liquidbounce-fabric.mixins.json
(FabricLoader) Loading 116 mods:                 ← LB is NOT among them
(LiquidBounce) Launching LiquidBounce v0.38.1 by CCBlueX
(LiquidBounce/CefBrowser) Finished loading (…/resource/liquidbounce/…, httpStatusCode=200)
```
LB confirmed **absent from the 116-mod enumeration**; loaded, initialized, and its web UI
served — entirely via the agent.

## Known PoC limitations (Fabric, same as vanilla)
- MCEF PR not merged upstream → needs the local `mcef-fabric` snapshot to be present.
- MCEF/libcef ClickGUI interaction can SIGILL under software GL (llvmpipe on this VM) —
  a VM/driver caveat, not the agent; enable modules via config/chat rather than ClickGUI clicks here.
- Dev-namespace launch (`official`) matches LB's AW; a production install would attach the
  agent to the real launcher's JVM args identically (the hook is namespace-agnostic).
