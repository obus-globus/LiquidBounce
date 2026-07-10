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

---

## Full-client PoC (P1–P3) + clean-room reproduction

`run.sh` (committed here) launches **full LiquidBounce v0.38.1 on unmodified vanilla
Minecraft 26.2** via the transforming classloader — no `-javaagent` surgery, no
hand-assembled classpath. It rebuilds the agent jar, `VanillaPlatform`, corrected
resources and the whole classpath from committed sources + Gradle output/caches.

**Reproduce:**
```
./gradlew classes processResources         # once, to populate build/classes + caches
docs/vanilla-agent-spike/run.sh            # launches on Xvfb :99 (override VSPIKE_DISPLAY)
```
Verified via a clean-room run (hand-built work dir moved aside, fresh `/tmp/vspike-run`):
reaches the LiquidBounce menu + ClickGUI, `config 'modules'` loaded, MCEF browser ready.
See `lb-cleanroom-reproduction.png`. The clean-room test caught (and the script now
fixes) two path bugs: an unquoted `find` broke on the space in the repo path, and a
missing trailing newline fused two classpath entries.

**Extra integration pieces the full client needed (beyond the spike):**
- Exclude `fabric-loader` + the dependency copy of `sponge-mixin` from the classpath —
  they ship rival Mixin services (`FabricGlobalPropertyService`) that get selected and
  NPE without a Knot launcher.
- `MixinExtrasBootstrap.init()` **after** the transformer exists (LB leans on
  `@WrapOperation`/`@ModifyExpressionValue`/`@Local`).
- A **transforming classloader** (not a bare `-javaagent`) so Mixin's runtime *synthetic*
  classes (`org.spongepowered.asm.synthetic.*`, from `@ModifyArgs`) can be generated on
  demand; a `ClassFileTransformer` can only transform existing bytes, not create classes.
- Self-load exactly the MC-owned packages (`net.minecraft`, `com.mojang.{blaze3d,math,
  realmsclient}`, `net.ccbluex`) and delegate everything else to the parent — otherwise
  JDK-module `IllegalAccessError` (xerces) and `VerifyError` (two `Screen` classes from
  split loaders). Keep authlib on the parent (it makes runtime anonymous classes).
- The loader falls back to un-mixed bytes if a single mixin fails to apply, so one bad
  mixin degrades a feature instead of crashing the game.

## Known PoC limitations (NOT regressions)

- **2 of ~150 mixins fall back** (load un-mixed, non-fatal): `MixinLocalPlayer`
  (`@Local` on `sendPosition` — LVT metadata missing in the vanilla jar) and
  `MixinChatComponent`. Their features are degraded; everything else applies. To fix
  for production: switch those to ordinal-based `@Local` or verify the target LVT.
- **Software-GL checkerboard terrain** in-world is an **llvmpipe (Mesa software GL)**
  artifact of this headless VM — not the agent. MCEF renders crisply because it's a
  separate software-rendered surface. On a real GPU the world renders normally.
- This is a **PoC via a throwaway launcher**, not a productionized `:vanilla` Gradle
  subproject. Productionizing = fold `run.sh` into a real subproject that emits a fat
  agent jar, fix the 2 mixins, and decide the MCEF/Via/DJL bundling policy.

---

## Functional smoke test — honest scorecard

Drove the client menu → creative world → ClickGUI → toggled modules across categories,
watching for toggle-but-no-effect and errors during operation.

**Works (verified):**
- **Boots consistently** — two independent `run.sh` launches, identical 2 mixin
  fallbacks, no intermittent classloader/verify errors between runs.
- **Full MCEF UI** — custom menu, ClickGUI (all categories), theme, fonts, ArrayList
  HUD (shows enabled modules), ClientChat (live network messages).
- **Render modules function** — **Xray** confirmed: terrain rendered transparent, ores
  + lava exposed (`lb-module-xray-working.png`) vs the solid landscape
  (`lb-inworld-clean-render.png`). Module toggle + on/off signal work across categories.
- **World renders correctly** in steady state (the checkerboard is only during initial
  chunk load, then clears — see the clean-render shot).

**Broken / degraded (honest):**
- **~20+ modules silently no-op** because `PlayerMoveEvent`,
  `PlayerNetworkMovementTickEvent` and `PlayerTickEvent` are dispatched **only** by
  `MixinLocalPlayer`, which falls back. So **most Movement modules and many
  Combat/Player modules toggle on but do nothing.** This is the load-bearing impact of
  the "2 fallback mixins" — one of them (`MixinLocalPlayer`) powers 17 core events.
  Not cosmetic.
- Combat silent server-side rotations + reach (`sendPosition`/`pick` hooks) are gone →
  aim/reach modules degraded.
- Vanilla chat enhancements (`MixinChatComponent`: copy-highlight, anti-clear) degraded;
  the ClientChat *module* is unaffected.

**Root cause + fix (small, known):** both fallbacks are **named-local** injectors
(`@ModifyVariable(method="sendPosition", name="rot")` and `@Local(name="lines")`) that
can't resolve because the vanilla 26.2 jar lacks LVT names for those two specific
methods (the other 12 named-`@Local` mixins resolve fine). Fix = switch those two to
**ordinal-based** `@Local`/`@ModifyVariable`. Once `MixinLocalPlayer` applies, the 17
events fire and the movement/combat modules work. This is the gating fix for "usable
client" vs "boots + render only".

**Stability caveat:** MCEF/Chromium crashed once (`libcef.so` SIGILL) under rapid
ClickGUI interaction on **software GL** (llvmpipe). Boot is stable; this is a Chromium
software-rendering fragility (likely absent on a real GPU), but a real risk under heavy
UI use. The vanilla-agent infrastructure (classloader/mixin/AW) ran clean throughout —
the crash was entirely inside CEF native code.

**Not tested:** a module *settings* value-change (the CEF crash cut the session short).
