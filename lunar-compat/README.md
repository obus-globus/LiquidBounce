# LiquidBounce <-> Lunar Client compatibility checks

Lunar Client runs Fabric mods against its **own build of Minecraft** - vanilla remapped to Mojang-named classes,
then reshaped by Lunar's "inflight" patches and its own mixins (Lunar's mod system is called *Ichor*). Because that
runtime diverges from vanilla, a LiquidBounce mixin whose injection point Lunar moved/renamed/removed silently fails
to apply (`InvalidInjectionException` / "target not found"), and the feature it backs breaks on Lunar. Lunar also
bundles some of its own libraries (e.g. a stripped `okhttp3`) that can shadow LiquidBounce's on Ichor's shared
classloader. Lunar updates frequently, so all of this breaks without warning.

This tooling turns the reactive "users report it's broken -> we fix it" loop into two automatic checks.

## The two checks

### 1. Static mixin check (no gameplay)

- **`scripts/fetch-and-bake.sh`** hits Lunar's public launch API (no account needed), downloads the current build's
  classpath jars + natives, and runs Lunar's `Genesis` bootstrap headless just far enough to **bake** its runtime
  classes (Genesis writes `bake.zip` early). The dotted-name entries are rewritten into a normal `lunar-classes.jar`
  - Lunar's actual remapped Minecraft.
- **`./gradlew checkLunarCompat -PlunarJar=lunar-compat/lunar-classes.jar`** resolves every LiquidBounce mixin's
  `@Mixin` target + injector selectors (`@Inject`/`@ModifyArg`/`@WrapOperation`/... `method` selectors and their `@At`
  INVOKE/FIELD/NEW targets) against those classes. A **BLOCKER** means the target class/method/injection point does
  not exist in Lunar's runtime - that mixin fails on Lunar. SUSPECTs are advisory (e.g. name-only `@Local` that can't
  be verified because Lunar stripped local names).

Catches: "a mixin silently stopped applying after a Lunar update". Credential-free, so it runs unattended.

### 2. Runtime boot check (Lunar + LiquidBounce)

- **`scripts/run-liquidbounce.sh`** fetches Lunar's current build, stages the freshly built LiquidBounce jar plus the
  matching `fabric-language-kotlin` into Lunar's Fabric mod directory, boots Lunar **with LiquidBounce loaded**
  headless, waits for init, then grades the log: did LiquidBounce launch, did any mixin injection fail, did any
  linkage error (`NoSuchFieldError`/`NoClassDefFoundError` from library shadowing) or shader/pipeline compile failure
  occur, did LiquidBounce's own fatal error handler fire.

Catches what the static check can't: classpath/library shadowing, shader/pipeline failures, `NoSuch*`/linkage errors.

> **Headless caveat (handled):** with no GPU, Lunar's WebOSR/Ultralight overlay segfaults a few seconds *after* init -
> this reproduces with zero mods and is a Lunar limitation, not a LiquidBounce bug. Every useful signal happens during
> init, before that, so the check harvests the init log and ignores a post-init WebOSR crash.

## Auto-provisioning & update-resistance

Nothing here is version-pinned by hand - both checks stay correct across Lunar updates and LiquidBounce bumps:

- **Minecraft version** - auto-detected from LiquidBounce's own `gradle/libs.versions.toml` (`mc_version` input / first
  script arg override it).
- **Lunar build** - always the current public build for that MC version (the launch API can't pin a historical build,
  which is the right default: "does it work on what users run *now*"). Each run records the tested Lunar commit +
  launcher version + timestamp.
- **fabric-language-kotlin** - LiquidBounce is Kotlin and Lunar doesn't ship FLK; the runtime check reads the exact
  version LiquidBounce's own `fabric.mod.json` requires and fetches it from FabricMC maven.
- The one update-fragile thing (Lunar's launch API request shape) lives in a single place, `scripts/lib-lunar.sh`,
  shared by both checks.

Only Lunar's `master` (release) branch is reachable publicly; `beta`/`staging` return `NO_PERMISSION_PRIVATE_BRANCH`
(need a Lunar account). Override with `LUNAR_BRANCH` / the `lunar_branch` workflow input.

## GitHub Action

`.github/workflows/lunar-compat.yml` runs both checks daily (and on demand via **Run workflow**), each in its own job,
publishing a report to the job summary and failing when LiquidBounce would break on the current Lunar build.

## Run locally

```bash
# needs: JDK 25 (for MC 26.2), Xvfb (headless), curl, python3, unzip
export JAVA_BIN=/path/to/jdk-25/bin/java

# static - no gameplay
bash lunar-compat/scripts/fetch-and-bake.sh            # MC version auto-detected; writes lunar-compat/lunar-classes.jar
./gradlew checkLunarCompat -PlunarJar=lunar-compat/lunar-classes.jar   # -PlunarStrict=true also fails on SUSPECTs
cat build/reports/lunar-compat.txt

# runtime - boots Lunar with LiquidBounce
./gradlew jar                                          # builds the LiquidBounce jar to stage into Lunar
bash lunar-compat/scripts/run-liquidbounce.sh          # MC + FLK auto-detected; boots headless and grades
cat build/reports/lunar-runtime-compat.txt
```

The static check engine is `buildSrc/src/main/kotlin/LunarCompatCheckTask.kt` (an ASM selector resolver pointed at
Lunar's baked classes).

The runtime check only passes once LiquidBounce can start under Lunar at all. Lunar bundles okhttp 3.14.9, which
predates okhttp's move to Kotlin, and its copy wins on Ichor's shared classloader, so LiquidBounce dies during init
with a `NoSuchFieldError` on `okhttp3.Headers.Companion` until the bundled okhttp is relocated out of its way.
