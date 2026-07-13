package net.ccbluex.liquidbounce.platform.injector;

import net.ccbluex.liquidbounce.platform.vanilla.VanillaPlatform;

/**
 * Platform used when LiquidBounce is injected at runtime.
 *
 * The loader-native impls ({@code FabricPlatform}/{@code NeoForgePlatform}) do startup-time registry/lifecycle work
 * in {@code buildCreativeTab} and {@code registerResourceReloadListeners} — registering a creative tab into a frozen
 * registry, subscribing to a mod-init reload event that has already fired. Applied post-startup (late attach) those
 * either throw or hang, so delegating to them wholesale breaks LB init. The vanilla no-ops are the correct late-attach
 * behaviour there (LB has its own fallbacks — e.g. it runs the theme reloader directly when registration returns false).
 *
 * So this inherits every safe vanilla behaviour and overrides ONLY {@link #isModLoaded} — a pure, side-effect-free
 * query — to consult the actual running loader (via reflection, so no compile/link dependency on the loader) so LB's
 * mod-compat checks (e.g. ViaFabricPlus) get correct answers instead of the vanilla stub's blanket false.
 */
public class InjectorPlatform extends VanillaPlatform {

    @Override
    public boolean isModLoaded(String id) {
        Boolean neoforge = neoforgeModLoaded(id);
        if (neoforge != null) return neoforge;
        Boolean fabric = fabricModLoaded(id);
        if (fabric != null) return fabric;
        return super.isModLoaded(id);
    }

    private static Boolean neoforgeModLoaded(String id) {
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            Object instance = modList.getMethod("get").invoke(null);
            if (instance == null) return null;
            return (Boolean) modList.getMethod("isLoaded", String.class).invoke(instance, id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean fabricModLoaded(String id) {
        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            if (instance == null) return null;
            return (Boolean) fabricLoader.getMethod("isModLoaded", String.class).invoke(instance, id);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
