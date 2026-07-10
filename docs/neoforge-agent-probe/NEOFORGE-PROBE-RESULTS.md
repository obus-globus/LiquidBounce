# NeoForge agent-injection probe

**Question (spike-gated):** can LiquidBounce be loaded on NeoForge by a premain Java agent —
NOT installed as a mod — the way it now is on vanilla and Fabric?

A prior static seam-map (subagent) concluded **NO**: "premain `ClassFileTransformer` can't
hook `Minecraft` — timing mismatch, sealed `ModuleLayer`, FML's classloader not yet created;
mixin configs locked at init." That is a *reasoned* wall from reading bytecode — the classes
it cited exist regardless of whether injection works, so the evidence doesn't actually support
the conclusion. We tested it empirically instead.

---

## Tier 1 — KILL-SHOT: premain agent transforms `Minecraft` on live NeoForge — **GREEN**

**Probe** (`src/nfprobe/NFProbe.java`): a `-javaagent` whose `premain` registers a whole-JVM
`ClassFileTransformer` (`inst.addTransformer(t, true)`). When `net.minecraft.client.Minecraft`
is defined, it injects a `System.out.println` into `<clinit>` and `run` using **JDK 25's
built-in `java.lang.classfile`** (JEP 484, final in JDK 24) — *zero external ASM*, so there is
no duplicate-ASM abort risk (which bit the Fabric agent).

**Substrate:** the real `:neoforge` `runClient` (MC 26.2, NeoForge 26.2.0.7-beta), launched via
`nf-probe.gradle` init script that **clears ModDevGradle's `ModFoldersProvider`** — its sole
job is registering LB as the dev mod `liquidbounce`, so clearing it means **LB is not a
discovered ModFile**. Other mods (sodium/lithium/mcef/immediatelyfast/neoforge) still load
normally via the module path.

**Result** (`nf-probe-airtight-proof.log`):
```
Reloading ResourceManager: vanilla, mod_resources, mod/sodium, mod/lithium,
                           mod/mcef, mod/immediatelyfast, mod/neoforge   ← NO liquidbounce
[NFPROBE] premain fired — java=25.0.3 retransformSupported=true redefineSupported=true
[NFPROBE] transform() SAW net/minecraft/client/Minecraft
          loader=net.neoforged.fml…TransformingClassLoader module=minecraft
[NFPROBE] transformed Minecraft (161997 -> 162397 bytes)
[NFPROBE-INJECTED] executing inside Minecraft.<clinit> … premain agent, NOT a mod
[NFPROBE-INJECTED] executing inside Minecraft.run   … premain agent, NOT a mod
Sound engine started    ← boot healthy post-transform (no VerifyError)
```

**What this establishes / refutes:**
- ✅ A premain JVM agent transforms `Minecraft` on NeoForge's `TransformingClassLoader`,
  with LB not a mod. Arbitrary bytecode injection into the game is possible via agent.
- ❌ Refutes the seam-map's central claim. `addTransformer(t, true)` fires for classes
  defined by **any** classloader — including `TransformingClassLoader` long after premain —
  so there is no "classloader not created yet / timing mismatch" window. The transformer sees
  `net/minecraft/client/main/Main` and `Minecraft` on the FML loader.
- ✅ `retransform`/`redefine` both report `supported=true`.

**Scope — what Tier 1 does NOT prove:** LB drives 150+ **Mixins** through FML's mixin service,
not raw transforms. Tier 1 proves the *hardest thing the analysis said was impossible* (touch
`Minecraft` post-`ModuleLayer`-seal via agent), but the full LB path depends on Mixin. That is
Tier 2.

## Reproduce
```
docs/neoforge-agent-probe/  →  build the agent, then run with the init script:
  javac --release 25 -d out src/nfprobe/NFProbe.java
  jar cfm /tmp/nfprobe.jar <manifest: Premain-Class: nfprobe.NFProbe, Can-Retransform-Classes: true> -C out nfprobe
  ./gradlew :neoforge:runClient --init-script nf-probe.gradle   # DISPLAY set; needs :neoforge built
```

---

## Tier 2 — Mixin-DEFER path (does the *real* LB mechanism work?) — IN PROGRESS
<!-- filled in after the FMLMixinService registration probe -->
