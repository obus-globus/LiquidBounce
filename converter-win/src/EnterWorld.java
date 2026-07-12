import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

/**
 * enterworld.jar (recreation of the lost Linux helper) — attach AFTER full-agent has initialized LB at the menu.
 * agent arg = level directory name under saves\ (default "LBWorld").
 *
 * On the MC main thread (Minecraft.execute):
 *   1. RE-FREEZE all registries. FullInjectAgent's bootstrap kick unfroze them (MappedRegistry.frozen=false) so LB
 *      could register its custom sound; world-load re-freezes registries and throws
 *      "Tags already present before freezing" if they are still unfrozen (docs 03-attach-frontier.md fix #14).
 *      FullInjectAgent does NOT re-freeze after the bootstrap (verified in source) — so we do it here, right
 *      before openWorld. MC's own freeze() early-returns on an already-frozen registry, so this is safe.
 *   2. mc.createWorldOpenFlows().openWorld(levelName, cancelRunnable).
 */
public class EnterWorld {
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        final String level = (args == null || args.isBlank()) ? "LBWorld" : args.trim();
        final ClassLoader sys = ClassLoader.getSystemClassLoader();
        final Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, sys);
        final Object mc = mcCls.getMethod("getInstance").invoke(null);
        Runnable work = () -> {
            try {
                refreezeRegistries(sys);
                Object flows = mcCls.getMethod("createWorldOpenFlows").invoke(mc);
                Runnable onCancel = () -> System.out.println("[ENTERWORLD] openWorld cancel-callback invoked");
                flows.getClass().getMethod("openWorld", String.class, Runnable.class).invoke(flows, level, onCancel);
                System.out.println("[ENTERWORLD] openWorld(\"" + level + "\") invoked on main thread");
            } catch (Throwable t) {
                System.out.println("[ENTERWORLD] FAILED -> " + t);
                t.printStackTrace();
            }
        };
        mcCls.getMethod("execute", Runnable.class).invoke(mc, work);
        System.out.println("[ENTERWORLD] scheduled openWorld(\"" + level + "\")");
    }

    /** set MappedRegistry.frozen = true on the root writable registry + every child registry (mirror image of
     *  FullInjectAgent.unfreezeRegistries). */
    static void refreezeRegistries(ClassLoader sys) throws Exception {
        Class<?> bir = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, sys);
        Field wr = bir.getDeclaredField("WRITABLE_REGISTRY"); wr.setAccessible(true);
        Object root = wr.get(null);
        Class<?> mapped = Class.forName("net.minecraft.core.MappedRegistry", false, sys);
        Field fz = mapped.getDeclaredField("frozen"); fz.setAccessible(true);
        int n = 0;
        if (mapped.isInstance(root)) { fz.setBoolean(root, true); n++; }
        for (Object reg : (Iterable<?>) root) if (mapped.isInstance(reg)) { fz.setBoolean(reg, true); n++; }
        System.out.println("[ENTERWORLD] re-froze " + n + " registries");
    }
}
