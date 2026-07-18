# LiquidBounce ⇄ Lunar Client compatibility check

Lunar Client runs Fabric mods against its **own build of Minecraft** — vanilla remapped to Mojang-named classes,
then reshaped by Lunar's "inflight" patches and its own mixins (Lunar's mod system is called *Ichor*). Because that
runtime diverges from vanilla, a LiquidBounce mixin whose injection point Lunar moved/renamed/removed silently fails
to apply (`InvalidInjectionException` / "target not found"), and the feature it backs breaks on Lunar. Lunar updates
frequently, so this breaks without warning.

This tooling turns the reactive "users report it's broken → we fix a mixin" loop into an automatic check.

## How it works

1. **`scripts/fetch-and-bake.sh`** hits Lunar's public launch API (no account needed), downloads the current build's
   classpath jars + natives, and runs Lunar's `Genesis` bootstrap headless just far enough to **bake** its runtime
   classes (Genesis writes `bake.zip` early). The dotted-name entries are rewritten into a normal
   `lunar-classes.jar` — Lunar's actual remapped 26.2 Minecraft.
2. **`./gradlew checkLunarCompat -PlunarJar=lunar-compat/lunar-classes.jar`** resolves every LiquidBounce mixin's
   `@Mixin` target + injector selectors (`@Inject`/`@ModifyArg`/`@WrapOperation`/… `method` selectors and their
   `@At` INVOKE/FIELD/NEW targets) against those classes. LiquidBounce and Lunar share the mojmap namespace, so
   selectors resolve directly; a **BLOCKER** means the target class/method/injection point does not exist in Lunar's
   runtime — i.e. that mixin fails on Lunar. SUSPECTs are advisory (e.g. name-only `@Local` that can't be verified
   because Lunar stripped local names).
3. The **`Lunar compatibility` GitHub Action** (`.github/workflows/lunar-compat.yml`) runs both daily and reports the
   failing mixins to the job summary, so a Lunar update that breaks LiquidBounce is caught before users notice.

## Which Lunar version is tested?

The launch API always serves the **latest** Lunar build for a given `(MC version, branch)` — you can't pin a
historical Lunar build (which is the right default: you want "does it work on what users run *now*"). What you can
pick:

- **MC version** — first arg to the script / the `mc_version` workflow input (default `26.2`; match LiquidBounce's
  target version).
- **Branch** — `LUNAR_BRANCH` env / `lunar_branch` workflow input. Only `master` (release) is reachable publicly;
  `beta`/`staging` return `NO_PERMISSION_PRIVATE_BRANCH` (need a Lunar account).

Every run records the exact tested build (Lunar's Genesis commit hash + launcher version + timestamp) to
`<output-jar>.meta`, and the GitHub Action prints it in the job summary, so a report always says *what* it ran against.

## Run locally

```bash
# needs: JDK 25 (MC 26.2), Xvfb (headless), curl, python3, unzip
JAVA_BIN=/path/to/jdk-25/bin/java bash lunar-compat/scripts/fetch-and-bake.sh 26.2 lunar-compat/lunar-classes.jar
./gradlew checkLunarCompat -PlunarJar=lunar-compat/lunar-classes.jar          # -PlunarStrict=true to also fail on SUSPECTs
cat build/reports/lunar-compat.txt
```

The check engine is `buildSrc/src/main/kotlin/LunarCompatCheckTask.kt` (the same selector-matching engine used for
the NeoForge divergence check, pointed at Lunar's baked classes).
