# NeoForge self-contained agent — stock zero-Gradle launch evidence (2026-07-10)

## agent staging (from the single jar, no -Dlb.*, no staging dir)
[NFAGENT] appended 10 root LB resources to system loader: [pipes-fork-server-default-log4j2.xml, git.properties, liquidbounce-neoforge.mixins.json, DebugProbesKt.bin, jcef.commit, icon.png, pytorch-engine.properties, LICENSE_Reflect, liquidbounce.mixins.json, LICENSE_mcef-neoforge]
[NFAGENT] self-contained: staged 50 bundled libs, 940 owned packages (data-driven), AT=true
[NFAGENT] hooked MixinFacade.finishInitialization (14486 -> 14940)
[NFAGENT] hooked AccessTransformerService.<init> (2849 -> 3257)
[NFAGENT] loaded LB AccessTransformer into FML AT engine
[NFAGENT] registered LB mixin configs into live FMLMixinService
[NFAGENT] installed LB child-first fallback (chained to original=net.neoforged.fml.classloading.ResourceMaskingClassLoader)
[NFAGENT] registered 940 LB packages into TCL.parentLoaders (mixin byte source)
[NFAGENT] hooked AddClientReloadListenersEvent.<init> (3229 -> 3620)
[NFAGENT] registered LB reload listeners via AddClientReloadListenersEvent (mod-bus replicated)

## FML mod list (LiquidBounce ABSENT — it is not a mod)
[13:05:52] [ForkJoinPool.commonPool-worker-2/INFO] [ne.ne.fm.lo.mo.lo.JarInJarDependencyLocator/]: Found 4 dependencies adding them to mods collection
  (mods: loader, earlydisplay, sodium, lithium, immediatelyfast — no liquidbounce)

## LB mixins APPLIED to net.minecraft.client.Minecraft, attributed 'from mod (unknown)' (agent-injected)
APP:liquidbounce.mixins.json:minecraft.client.MinecraftAccessor from mod (unknown)
APP:liquidbounce.mixins.json:minecraft.client.MixinMinecraft from mod (unknown)

## full LB init
[13:06:00] [Render thread/INFO] [LiquidBounce/]: Launching LiquidBounce v0.38.1 by CCBlueX
[13:06:01] [Render thread/INFO] [LiquidBounce/]: Loaded 36 Render Pipelines.
[13:06:04] [Render thread/INFO] [LiquidBounce/ConfigSystem/]: Successfully loaded config 'theme'.
[13:06:04] [Render thread/INFO] [LiquidBounce/ConfigSystem/]: Successfully stored config 'theme'.
[13:06:05] [Render thread/INFO] [LiquidBounce/ConfigSystem/]: Successfully loaded config 'theme'.
[13:06:05] [Render thread/INFO] [LiquidBounce/ConfigSystem/]: Successfully stored config 'theme'.

## LB classes served by the bundled loader (lb-agent-loader / liquidbounce.jar)
lb-agent-loader/net.ccbluex.liquidbounce.api.core.HttpClient.clientHttpApiInterceptor
lb-agent-loader/net.ccbluex.liquidbounce.api.interceptors.CacheBlacklistInterceptor.intercept
lb-agent-loader/net.ccbluex.liquidbounce.authlib.interceptor.DefaultHeaderInterceptor.intercept
lb-agent-loader/net.ccbluex.liquidbounce.event.SuspendHandlerBehavior
lb-agent-loader/net.ccbluex.liquidbounce.integration.backend.backends.cef.CefBrowser.
lb-agent-loader/net.ccbluex.liquidbounce.integration.backend.backends.cef.CefBrowserBackend.createBrowser
lb-agent-loader/net.ccbluex.liquidbounce.integration.backend.BrowserBackend.createBrowser
lb-agent-loader/net.ccbluex.liquidbounce.integration.screen.ScreenManager
