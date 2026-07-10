# Self-contained Fabric agent (productionization)

A single `-javaagent:liquidbounce-agent-fabric.jar` that loads full LiquidBounce into a **stock
Fabric 26.2 install** — not as a mod — with **zero Gradle, zero `-Dlb.*` dev paths, zero external
staging**. The jar carries LB's classes + its full non-loader dependency tree + the AccessWidener;
`SCHook` extracts the bundled libs to a temp dir at premain, adds them to Knot, applies the AW via
ClassTweaker, and registers LB's mixin configs into the live loader Mixin. The native `libcef` is
NOT bundled — LB's `MCEFDownloadManager` downloads it at runtime (verified).

## Build (proper Gradle task, reproducible)
    ./gradlew fabricSelfContainedAgentJar
    # -> build/agent/liquidbounce-agent-fabric.jar  (~57 MB, 74 bundled libs, Premain-Class: scagent.SCAgent)

The bundle set is filtered from `runtimeClasspath` by maven **group** (LB-owned groups kept;
MC/loader/fabric-api/optional-mods dropped) in `build.gradle.kts` — mechanical, no hand-listing.

## Attach (a real user)
Install fabric-api, then add to the launcher's JVM args:
    -javaagent:/path/to/liquidbounce-agent-fabric.jar
Nothing else. (kotlin/DJL/mcef-Java/etc. are all inside the agent — no FLK, no mcef mod needed.)

## Proven (this VM)
`selfcontained-stock-fabric-proof.log`: on stock zero-Gradle Fabric 26.2, one `-javaagent` jar →
`staged LB + 73 bundled deps onto Knot` → AW applied → `Launching LiquidBounce v0.38.1` → Creative
Tabs + 36 Render Pipelines + Sound engine, **no missing-dep crash**, LB **absent** from the mod list.
Visual in-world/MCEF proof stays blocked on this software-GL/offline VM (MCEF downloads libcef +
needs a GPU); Fly is already proven functional (+51 Fabric / +33.76 NeoForge).
