# Vanilla self-contained agent — bare-vanilla launch evidence (2026-07-10)

## staging from the single fat jar (no Gradle, no hand-assembled cp, no -Dlb.*)
[VSPIKE] VanillaLauncher (self-contained): staging bundled LB payload
[VSPIKE] staged 138 bundled LB jars to /tmp/lb-vanilla-agent-13555175004918877097
[VSPIKE] AccessWidener: 131 directives
[VSPIKE] +config liquidbounce.mixins.json
[VSPIKE] +config liquidbounce-fabric.mixins.json
[VSPIKE] MixinExtras bootstrapped
[VSPIKE] phase=DEFAULT
[VSPIKE] launching Minecraft via transforming loader

## full LB init on bare vanilla MC 26.2
[13:23:21] [Render thread/INFO]: Launching LiquidBounce v0.38.1 by CCBlueX
[13:23:24] [Render thread/INFO]: Loaded 39 Render Pipelines.
[13:23:25] [Render thread/INFO]: Successfully loaded config 'theme'.
[13:23:25] [Render thread/INFO]: Successfully stored config 'theme'.
[13:23:26] [Render thread/INFO]: Initializing browser...
[13:23:26] [Render thread/INFO]: Falling back to software rendering for browser
[13:23:26] [Render thread/INFO]: Successfully initialized browser.
[13:23:27] [Render thread/INFO]: Successfully loaded config 'theme'.
[13:23:27] [Render thread/INFO]: Successfully stored config 'theme'.
[13:23:27] [Render thread/INFO]: Successfully loaded config 'modules'.
[13:23:27] [Render thread/INFO]: Successfully stored config 'modules'.
[13:23:27] [Render thread/INFO]: Successfully loaded config 'settings'.
[13:23:27] [Render thread/INFO]: Successfully stored config 'settings'.
[13:23:27] [Render thread/INFO]: Browser backend is ready. Initializing browser...

## mixin fallbacks: 0 (zero = all ~150 LB mixins applied)
