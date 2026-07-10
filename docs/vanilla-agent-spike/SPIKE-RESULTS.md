# Vanilla-agent de-risking spike — RESULTS (GREEN)

Throwaway spike proving the vanilla Java-agent approach is viable, before building the
real `:vanilla` subproject. It retires all four showstoppers the adversarial review
raised. **Verdict: the approach works.**

## What was proven (all empirically, on MC 26.2 + the exact Mixin LB uses)

1. **Standalone Mixin bootstrap over `java.lang.instrument` works.** A minimal
   `IMixinService` + `IMixinServiceBootstrap` + `IGlobalPropertyService` (extending
   `MixinServiceAbstract`, ~12 methods) drives `sponge-mixin:0.17.3+mixin.0.8.7` from a
   `premain`. Proof (in-process): a `@Inject` on a dummy class fired and changed its
   return value.

2. **Mixin applies to the REAL vanilla `net.minecraft.client.Minecraft`.** Offline
   transform of `Minecraft.class`: `changed=true`, 148358 → 149878 bytes. Live under a
   full MC launch:
   ```
   [VSPIKE] >>> Mixin applied to vanilla net.minecraft.client.Minecraft (getWindow HEAD) <<<
   ```

3. **The AccessWidener is applied by the agent, cross-class, at runtime.** The
   131-directive `liquidbounce.accesswidener` (namespace `official` = Mojmap, matches
   the vanilla 26.2 jar with no remap) widened `Minecraft.user` from `0x12`
   (private+final) to `0x1` (public). A cross-class `getField("user")` (public-only
   lookup) then succeeded at runtime:
   ```
   [VSPIKE] >>> AccessWidener OK: read Minecraft.user cross-class via getField (value=net.minecraft.client.User@...) <<<
   ```

4. **Vanilla MC 26.2 fully launches under the agent** — to the main menu, on Xvfb +
   software GL (see `mc-mainmenu-under-agent.png`). The agent does not break the game.

## The non-obvious fixes (these are the spike's real value)

A from-scratch attempt would stall on all of these:

- **ASM `Implementation-Version` must survive fat-jar packaging.** Mixin's
  `CompatibilityLevel.JAVA_25.isSupported()` calls `ASM.isAtLeastVersion(9, 7)`, which
  reads ASM's impl version via `Package.getImplementationVersion()` — i.e. the ASM
  jar's manifest. Merging ASM into one fat jar erases it (reads `0.0`), so JAVA_25 is
  **silently rejected** ("could not be set ... ASM 9.0"). Fix: add a per-package
  manifest section to the agent jar:
  ```
  Name: org/objectweb/asm/
  Implementation-Version: 9.9.1
  ```

- **`MixinPlatformManager.inject()` does NOT advance the current phase.** After
  `init()` + `addConfiguration()` + `prepare()/inject()`, the current phase is still
  `PREINIT`, so DEFAULT-phase configs are marked *visited but never prepared* — they
  apply nothing, with **no error**. Fix: force
  `MixinEnvironment.gotoPhase(INIT)` then `gotoPhase(DEFAULT)` (package-private; via
  reflection in the spike — the real impl should wire a phase consumer).

- **Bundle the Fabric fork, not upstream Mixin.** Only
  `net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7` has `CompatibilityLevel.JAVA_25`;
  bundle it plus unshaded ASM ≥ 9.7 (used 9.9.1). Point service discovery at the custom
  service via `META-INF/services` (only ours, to avoid loading the absent
  LaunchWrapper/ModLauncher services) + `-Dmixin.bootstrapService`.

- **Constructor `@At("HEAD")` must be static** (it lands before `super()`); for an
  instance handler inject after super — `@At("TAIL")`, or (used here for a fast proof
  that doesn't wait on the slow software-GL constructor) an early instance method like
  `getWindow`.

## How to rebuild/run the spike

Sources in `src/`. Fat agent jar = unpacked `sponge-mixin:0.17.3` + ASM 9.9.1 +
mixinextras-common + compiled `vspike/*` + `vspike.mixins.json` +
`liquidbounce.accesswidener` + `agent-manifest.mf` (note the ASM per-package section).
Launch raw MC 26.2 (`net.minecraft.client.main.Main`) with the piston libraries on the
classpath and `-javaagent:agent.jar`, `env -u WAYLAND_DISPLAY DISPLAY=:99`.

## Implication for the real build

The mapping problem is gone (26.2 is Mojmap), the bootstrap + AW + mixin mechanics are
proven, and MC runs under the agent. The `:vanilla` subproject (plan P0–P3) is now a
matter of engineering (fat-jar packaging, `VanillaPlatform`, the loader-API shims,
excluding MCEF/Via link deps) rather than unproven risk.
