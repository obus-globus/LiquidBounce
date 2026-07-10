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
