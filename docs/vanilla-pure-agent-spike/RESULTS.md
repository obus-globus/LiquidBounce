# Vanilla as a pure `-javaagent` — feasibility spike: **BREACHED (viable)**

**Question:** can vanilla run as a pure `-javaagent` (no custom launcher main class),
like Fabric/NeoForge, instead of the `VanillaLauncher` transforming-classloader?

**The one blocker** (from the vanilla spike history): a premain `-javaagent` can
transform system-classloader MC fine (mixins + AW apply), but LB's `@ModifyArgs`
mixins make Mixin generate **runtime synthetic classes**
(`org.spongepowered.asm.synthetic.args.Args$N`) and instantiate them at the call
site. A `ClassFileTransformer` can't fabricate a class nothing requests, so
system-loaded MC hits `ClassNotFoundException: …synthetic.args.Args$1`. That's why
the productionized path pivoted to a transforming classloader (whose
`findClass(synthetic)` reactively generates the bytes).

**Result: the wall is breachable.** Kill-shot passed on the first real attempt.

## What was proven (empirically, stock MC 26.2, pure `-javaagent`)

Launched `java -javaagent:agent.jar -cp <bare-vanilla MC+piston>
net.minecraft.client.main.Main …` — **no `VanillaLauncher`, no custom main class** —
with one `@ModifyArgs` mixin on the earliest guaranteed call in `Main.main`
(`OptionParser.accepts(String)`, ordinal 0). Log (`pure-agent-killshot-proof.log`):

```
[PURE] pure -javaagent premain (no launcher main class)
[PURE] transformer registered; premain done
[PURE] DEFINED synthetic into system loader: org.spongepowered.asm.synthetic.args.Args$1 (1250 bytes)
[PURE] >>> @ModifyArgs FIRED on Main.main OptionParser.accepts — argCount=1 arg0=demo (synthetic Args resolved, no CNF) <<<
```

- `@ModifyArgs` applied to a MC class and **fired**, reading the wrapped arg.
- The synthetic `Args$1` resolved with **zero `ClassNotFoundException`** anywhere.
- MC ran **on** past that point to full game init (Setting user, LWJGL backend,
  ResourceManager reload, Realms auth) — not a crash at the synthetic.

The original approach hits `CNF: …synthetic.args.Args$1` at exactly this call; the
only change here is the synthetic-injection below, which flips it to success — so
the injection is the cause, not incidental.

## The mechanism that breaches it (`src/vspike/PureAgent.java`)

Under a pure `-javaagent`, MC + sponge-mixin (bundled in the agent jar, appended to
the system classpath) + the synthetic namespace all live on the **system
classloader**. So the transforming-classloader's `findClass(synthetic)` step can be
replicated by *injection*:

1. In the `ClassFileTransformer`, after `transformer.transformClassBytes(mcClass)`,
   scan the transformed bytecode's constant pool for
   `org/spongepowered/asm/synthetic/*` references.
2. For each, `transformer.generateClass(env, name)` produces the synthetic's bytes
   (the same call the transforming loader uses); recurse to define its own synthetic
   deps first.
3. Define it into the **system classloader** via reflective
   `ClassLoader.defineClass(name, bytes, 0, len)` — *before* returning the
   transformed MC bytes, so system-loaded MC resolves the synthetic when it runs.

**Requirement:** `--add-opens java.base/java.lang=ALL-UNNAMED` for the reflective
`defineClass` (same flag the offline-MCEF path already needs). A `MethodHandles`
lookup would need a seed class in the synthetic package, which doesn't exist up
front, so reflective `defineClass` is the simpler route.

## Implication

Vanilla-as-pure-agent is viable: the synthetic wall — the sole reason for the
custom `VanillaLauncher` main class — is breachable by eagerly defining Mixin's
synthetics into the system loader. Productionizing (folding this into a real pure
`-javaagent` vanilla artifact, exercising LB's *full* `@ModifyArgs`/`@ModifyArgs`
+ `@Redirect`/`@WrapOperation` synthetic set end-to-end, and the `@ModifyArgs`
runtime-args path) is a separate build, not done here — this spike only answers the
feasibility question.
