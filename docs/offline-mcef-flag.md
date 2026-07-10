# `-PbundleMcefNative` — optional offline MCEF (temporary)

An **opt-in, off-by-default** Gradle flag on all three self-contained agents
(Fabric, NeoForge, vanilla) that **also bundles MCEF's native `libcef`** into the
artifact, so MCEF initializes with **zero runtime download** (fully offline). The
default artifacts stay lean and download the native on load as usual.

This is explicitly marked temporary/optional: the native is ~350 MB, so a
flag-on jar is ~190–250 MB vs the lean ~54–111 MB.

## Build

```
# default (lean, download-on-load) — unchanged:
./gradlew :fabricSelfContainedAgentJar
./gradlew :neoforge:neoforgeSelfContainedAgentJar
./gradlew :vanillaSelfContainedAgentJar

# offline (bundles the native libcef):
./gradlew :fabricSelfContainedAgentJar          -PbundleMcefNative
./gradlew :neoforge:neoforgeSelfContainedAgentJar -PbundleMcefNative
./gradlew :vanillaSelfContainedAgentJar         -PbundleMcefNative
```

The native is sourced from MCEF's platform cache on this box
(`getPlatformDirectory()` — `<gameDir>/LiquidBounce/mcef/libraries/<jcef-commit>/linux_amd64`,
the same native MCEF downloaded on a prior successful init). Override the source
with `-PmcefNativeDir=<dir>`. Only the current platform (linux_amd64) is bundled —
this is a single-box convenience, not a cross-platform packaging step.

Jar sizes: Fabric 57 → 192 MB, NeoForge 54 → 190 MB, vanilla 111 → 247 MB.

## How it works (`McefNative`, one copy per agent package)

When the flag is on, the native goes into the jar under `mcef-native/`. At
premain/launch each agent calls `McefNative.stageIfBundled(selfJar, tag)` which:
1. no-ops if `mcef-native/libcef.so` isn't present (lean build → normal download);
2. else extracts `mcef-native/**` to `<tmpdir>/lb-mcef-native` (once, keyed by the
   marker file; re-used across launches), and `chmod +x` the `jcef_helper`/`chrome-sandbox`;
3. reflectively sets the **`PROVIDED_JCEF_PATH`** env var to that dir.

MCEF's `MCEFDownloadManager.newResourceManager()` reads `System.getenv("PROVIDED_JCEF_PATH")`;
when set it returns an `MCEFProvidedResourceManager` whose `requiresDownload()` is
**unconditionally `false`** and whose `getPlatformDirectory()` is that dir — so MCEF
loads the native from the bundle and never touches the network.

**Requires `--add-opens java.base/java.lang=ALL-UNNAMED`** at launch (injecting an
env var into a running JVM touches `java.lang.ProcessEnvironment`). Without it the
injection fails *gracefully* and MCEF falls back to download-on-load — the agent
doesn't break. The vanilla `run-bare-vanilla.sh` and the Fabric/NeoForge launches
used for the evidence pass this flag.

## Verified — offline MCEF init on ALL THREE (`*/offline-mcef-proof.log`)

Each launched with the flag-on jar, from a **cold** temp (native deleted first, so it
must come from the bundle), on a box that *can* reach `api.liquidbounce.net` (the
404s in the logs are LB's own version-check) — so MCEF *could* have downloaded but
didn't:

| target   | native staged from bundle | `Successfully initialized browser` | `Downloading JCEF` attempts |
|----------|---------------------------|------------------------------------|-----------------------------|
| vanilla  | yes (`[VSPIKE]`)          | yes                                | 0 |
| NeoForge | yes (`[NFAGENT]`)         | yes                                | 0 |
| Fabric   | yes (`[SCAGENT]`)         | yes (+ `Initializing Browser API url=…`) | 0 |

(rendered as a list because Discord can't show tables — see the per-target logs.)

## Honest render note

The flag removes the **download** dependency — that is what it targets, and MCEF's
browser backend initializes (software rendering) with zero network on **all three**.

On-screen compositing is a *separate* concern from the download:
- **Vanilla** maps its GLFW window, so LB's MCEF menu renders visibly on-screen
  (`../vanilla-agent-selfcontained/lb-selfcontained-on-bare-vanilla.png`).
- **Fabric / NeoForge**: the MCEF backend + Browser API initialize fine offline, but
  visible on-screen MCEF UI on the agent-injection path is gated by the separate
  GLFW-window/compositing behavior on this headless VM (software GL), independent of
  where the native came from. The download is no longer a factor on those either.
