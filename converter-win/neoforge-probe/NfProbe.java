import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;

/** agentmain probe: inspect the live NeoForge TCL + FMLMixinService seams the converter platform needs.
 *  Dumps everything to stdout (target JVM's console/log). No mutation. */
public class NfProbe {
    static final String TCL = "net.neoforged.fml.classloading.transformation.TransformingClassLoader";
    public static void agentmain(String a, Instrumentation inst) {
        StringBuilder sb = new StringBuilder("\n===== [NFPROBE] BEGIN =====\n");
        try {
            // 1. Find TCL via a loaded net.minecraft.* class
            ClassLoader tcl = null; Class<?> mc = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().startsWith("net.minecraft.") && c.getClassLoader() != null
                        && c.getClassLoader().getClass().getName().equals(TCL)) { tcl = c.getClassLoader(); mc = c; break; }
            }
            sb.append("TCL found=").append(tcl).append("  via=").append(mc).append("\n");
            if (tcl != null) {
                sb.append("TCL module=").append(mc.getModule()).append(" agentModule=").append(NfProbe.class.getModule()).append("\n");
                for (Class<?> k = tcl.getClass(); k != null; k = k.getSuperclass()) {
                    sb.append("  TCL class ").append(k.getName()).append(" module=").append(k.getModule()).append("\n");
                    for (Field f : k.getDeclaredFields())
                        if (f.getName().contains("parent") || f.getName().contains("fallback") || ClassLoader.class.isAssignableFrom(f.getType()) || Map.class.isAssignableFrom(f.getType()))
                            sb.append("    field ").append(Modifier.toString(f.getModifiers())).append(" ").append(f.getType().getSimpleName()).append(" ").append(f.getName()).append("\n");
                }
                for (Method m : tcl.getClass().getMethods())
                    if (m.getName().contains("Fallback") || m.getName().contains("fallback") || m.getName().contains("arent"))
                        sb.append("  TCL method ").append(m).append("\n");
            }
            // 2. Mixin service via the TCL's Mixin copy
            Class<?> svcCls = Class.forName("org.spongepowered.asm.service.MixinService", false, tcl);
            sb.append("MixinService class loader=").append(svcCls.getClassLoader()).append("\n");
            Object svc = svcCls.getMethod("getService").invoke(null);
            sb.append("getService()=").append(svc.getClass().getName()).append(" loader=").append(svc.getClass().getClassLoader()).append("\n");
            for (Class<?> k = svc.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                sb.append("  svc class ").append(k.getName()).append("\n");
                for (Method m : k.getDeclaredMethods())
                    if (m.getName().toLowerCase().contains("transform") || m.getName().toLowerCase().contains("config") || m.getName().toLowerCase().contains("addmixin"))
                        sb.append("    m ").append(Modifier.toString(m.getModifiers())).append(" ").append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append(Arrays.toString(m.getParameterTypes())).append("\n");
                for (Field f : k.getDeclaredFields())
                    sb.append("    f ").append(Modifier.toString(f.getModifiers())).append(" ").append(f.getType().getSimpleName()).append(" ").append(f.getName()).append("\n");
            }
            // 3. Try to locate the live IMixinTransformer: scan svc fields + static holders
            Class<?> iTr = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, tcl);
            sb.append("IMixinTransformer=").append(iTr).append("\n");
            Object tr = findTransformer(svc, iTr, sb);
            sb.append("transformer=").append(tr == null ? "NULL" : tr.getClass().getName()).append("\n");
            if (tr != null) {
                for (Class<?> k = tr.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields())
                        sb.append("    tr.f ").append(f.getType().getSimpleName()).append(" ").append(f.getName()).append("\n");
                // processor.transformedCount ?
                try { Field fp = tr.getClass().getDeclaredField("processor"); fp.setAccessible(true); Object proc = fp.get(tr);
                    sb.append("  processor=").append(proc.getClass().getName()).append("\n");
                    for (Field f : proc.getClass().getDeclaredFields())
                        sb.append("    proc.f ").append(f.getType().getSimpleName()).append(" ").append(f.getName()).append("\n");
                } catch (Throwable t) { sb.append("  processor lookup: ").append(t).append("\n"); }
            }
        } catch (Throwable t) {
            sb.append("PROBE ERROR: ").append(t).append("\n");
            for (StackTraceElement e : t.getStackTrace()) sb.append("   at ").append(e).append("\n");
        }
        sb.append("===== [NFPROBE] END =====\n");
        System.out.println(sb);
    }

    static Object findTransformer(Object svc, Class<?> iTr, StringBuilder sb) {
        // (a) a getTransformer()-style method on the service
        for (Class<?> k = svc.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods())
                if (m.getParameterCount() == 0 && iTr.isAssignableFrom(m.getReturnType())) {
                    try { m.setAccessible(true); Object r = m.invoke(m.getReturnType().isInstance(svc) ? svc : svc); if (r != null) { sb.append("  found via method ").append(m.getName()).append("\n"); return r; } }
                    catch (Throwable t) { sb.append("  method ").append(m.getName()).append(" -> ").append(t).append("\n"); }
                }
        // (b) a field holding an IMixinTransformer on the service or its statics
        for (Class<?> k = svc.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields())
                if (iTr.isAssignableFrom(f.getType())) {
                    try { f.setAccessible(true); Object r = f.get(Modifier.isStatic(f.getModifiers()) ? null : svc); if (r != null) { sb.append("  found via field ").append(f.getName()).append("\n"); return r; } }
                    catch (Throwable t) { sb.append("  field ").append(f.getName()).append(" -> ").append(t).append("\n"); }
                }
        // (c) MixinEnvironment.getActiveTransformer() static, if present
        try {
            Class<?> me = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", false, svc.getClass().getClassLoader());
            for (Method m : me.getMethods())
                if (m.getParameterCount() == 0 && Modifier.isStatic(m.getModifiers()) && iTr.isAssignableFrom(m.getReturnType())) {
                    Object r = m.invoke(null); if (r != null) { sb.append("  found via MixinEnvironment.").append(m.getName()).append("\n"); return r; }
                }
        } catch (Throwable t) { sb.append("  env transformer lookup -> ").append(t).append("\n"); }
        return null;
    }
}
