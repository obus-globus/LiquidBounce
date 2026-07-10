# Vanilla pure `-javaagent` — LB as a true drop-in agent (supersedes the launcher)

`liquidbounce-agent-vanilla-pure.jar` attaches to **stock `net.minecraft.client.main.Main`**
on bare vanilla 26.2 with **no launcher, no custom main class** — a `Premain-Class`
agent exactly like the Fabric/NeoForge ones. This **supersedes** the
`VanillaLauncher` main-class artifact (`vanillaSelfContainedAgentJar`): vanilla is
now a real drop-in agent, attached the same way as the other two loaders.

```
./gradlew :vanillaPureAgentJar [-PbundleMcefNative]
java -javaagent:liquidbounce-agent-vanilla-pure.jar \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -cp <bare-vanilla MC + piston libs> net.minecraft.client.main.Main <mc args>
```

## Architecture (everything on the system loader)

Unlike the launcher (which stood up a transforming classloader + a separate
`libLoader`), the pure agent puts **everything on the system classloader**:

- `premain` extracts the bundled LB payload (classes + resources + full dep tree +
  kotlin) to temp and `appendToSystemClassLoaderSearch`es each jar — so LB, kotlin
  and MC share one loader. (This also dissolves the JOML/kotlin split the launcher
  needed the kotlin-at-root workaround for — no workaround here.)
- MC is transformed **in place** by a `ClassFileTransformer` (AW + Mixin), reusing
  the standalone VSpike Mixin service.
- Optional offline MCEF via `McefNative` (`-PbundleMcefNative`).

## The synthetic wall + how it's breached (the load-bearing part)

`@ModifyArgs`/`@WrapOperation`/`@Local`-style injectors make Mixin generate runtime
**synthetic classes** that a `ClassFileTransformer` can't fabricate on demand. The
agent injects them into the system loader instead. After transforming a class, it
scans the transformed bytecode's `CONSTANT_Class` entries (via ASM) for synthetics —
**two families**:

- **sponge** `org.spongepowered.asm.synthetic.*` (e.g. `args.Args$N` from `@ModifyArgs`), and
- **MixinExtras** `<target>$Anonymous$<hash>` (from `@WrapOperation`/`@Local`), which
  live in the **target's own package**.

For each, `transformer.generateClass(env, name)` produces the bytes (recursing for
synthetic-refs-of-synthetics), and it is defined into the system loader — **before**
the transformed class is returned, so system-loaded MC resolves it when it runs.

**Signed-jar handling (critical):** MC's jar is *signed*, and MixinExtras synthetics
go into MC packages (`net.minecraft.*`, `com.mojang.authlib.*`). Defining an unsigned
class into a signed package throws `SecurityException: signer information does not
match`. Fix: define each synthetic with the **ProtectionDomain of the class it was
found in** (the `pd` from the transform callback) — a signed target contributes its
signed PD, so the per-package signer check passes; sponge synthetics (unsigned
namespace) get a null PD, consistently. Doing it at the referencing class's transform
(not eagerly at premain) guarantees the package's signer is the target's.

## Verified (milestones)

- **(a) buildable pure-agent artifact** — `:vanillaPureAgentJar`, `Premain-Class:
  vspike.PureVanillaAgent`, no `Main-Class`. 112 MB lean / 249 MB with the native.
- **(b) full LB init on stock `Main`** (`pure-agent-full-init-proof.log`): 138 LB jars
  appended, `Launching LiquidBounce v0.38.1`, 39 render pipelines, configs
  modules/settings loaded, `CefBrowser(visible=true) is ready`. Synthetics resolved
  across **both families and signed packages** (`org.spongepowered.asm.synthetic.args.Args$N`,
  `net.minecraft.world.level.Level$Anonymous$…`, `com.mojang.authlib.…MinecraftClient$Anonymous$…`)
  with **zero synthetic `ClassNotFoundException`, zero signer errors, zero mixin-apply
  failures**.
- **(c) rendered MCEF menu, offline** (`lb-mcef-menu-pure-agent-offline.png`,
  `pure-agent-offline-mcef-proof.log`): `-PbundleMcefNative`, native staged from the
  bundle, **0 `Downloading JCEF`**, LB's MCEF main menu renders. Window confirmed
  owned by the live agent JVM (`_NET_WM_PID` == java pid). **Clean-room**: cold
  `--rerun-tasks` build + fresh run reproduces full init + browser-ready.

## Honest coverage note

The synthetic set exercised at full startup → menu was **6 synthetics across all
injector families** (sponge `Args` + MixinExtras `$Anonymous$`) in both signed and
unsigned packages, 0 failures. LB's *complete* set (movement/combat/world mixins)
only fully fires **in-world**; I could not auto-load a world here — `--quickPlaySingleplayer`
is swallowed under LB's MCEF menu, and GUI-driving is fragile on this VM's software
GL. So in-world synthetic coverage is **not empirically exercised** in this run.

The mechanism is, however, complete **by construction**: every Mixin synthetic is
instantiated at an injection site inside a transformed target, so it appears in that
target's `CONSTANT_Class` pool and is caught by the scan (recursively for
synthetic-of-synthetic); nothing relies on the target class being known ahead of
time. No synthetic went uncaught in any run. If a future in-world pass ever surfaces
a synthetic the scan misses, that would be the thing to flag — none did here.
