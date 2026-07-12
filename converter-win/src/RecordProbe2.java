import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;

/**
 * RecordProbe2.jar — MINIMAL, LB-FREE reproducer for bug #22's suspected root cause:
 * "JVMTI RetransformClasses on an already-loaded RECORD class corrupts its field metadata".
 *
 * Attach to a BARE vanilla MC 26.2 at the menu (no other agent!). Sequence, all on the Attach Listener thread:
 *   phase A (baseline):   force-load GuiMessage -> getDeclaredFields -> construct via canonical ctor.
 *   phase B (identity):   retransformClasses with a transformer that returns a byte-identical CLONE of the
 *                         incoming buffer (forces the full redefinition pipeline, zero content change, no ASM).
 *   phase C (post):       construct again -> getDeclaredFields again (crash/NoSuchFieldError here = pure JVM
 *                         redefinition bug, converter exonerated).
 * agent arg "line" additionally does the same for GuiMessage$Line.
 */
public class RecordProbe2 {
    static final String GM = "net.minecraft.client.multiplayer.chat.GuiMessage";

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        System.out.println("[RECPROBE] start args=" + args);
        Class<?> gm = Class.forName(GM, true, sys);
        Class<?> comp = Class.forName("net.minecraft.network.chat.Component", true, sys);
        Class<?> sig = Class.forName("net.minecraft.network.chat.MessageSignature", false, sys);
        Class<?> src = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageSource", true, sys);
        Class<?> tag = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageTag", false, sys);
        Object text = comp.getMethod("literal", String.class).invoke(null, "recprobe");
        Object sysClient = src.getField("SYSTEM_CLIENT").get(null);
        Constructor<?> ctor = gm.getDeclaredConstructor(int.class, comp, sig, src, tag);

        reflect("A.reflect", gm);
        construct("A.construct", ctor, text, sysClient);

        // phase B: identity retransform
        final boolean[] fired = {false};
        ClassFileTransformer ident = new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                if (n != null && n.equals(GM.replace('.', '/'))) { fired[0] = true;
                    System.out.println("[RECPROBE] B.identity transformer fired, bytes=" + b.length);
                    return b.clone(); }
                return null;
            }
        };
        inst.addTransformer(ident, true);
        try { inst.retransformClasses(gm); System.out.println("[RECPROBE] B.retransform OK (fired=" + fired[0] + ")"); }
        catch (Throwable t) { System.out.println("[RECPROBE] B.retransform FAILED:"); t.printStackTrace(System.out); }
        finally { inst.removeTransformer(ident); }

        construct("C.construct", ctor, text, sysClient);
        reflect("C.reflect", gm);

        if (args != null && args.contains("line")) {
            Class<?> line = Class.forName(GM + "$Line", true, sys);
            reflect("L.reflect-baseline", line);
            ClassFileTransformer identL = new ClassFileTransformer() {
                public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                    if (n != null && n.equals((GM + "$Line").replace('.', '/'))) return b.clone();
                    return null;
                }
            };
            inst.addTransformer(identL, true);
            try { inst.retransformClasses(line); System.out.println("[RECPROBE] L.retransform OK"); }
            catch (Throwable t) { System.out.println("[RECPROBE] L.retransform FAILED:"); t.printStackTrace(System.out); }
            finally { inst.removeTransformer(identL); }
            reflect("L.reflect-post", line);
        }
        System.out.println("[RECPROBE] done");
    }

    static void reflect(String tagName, Class<?> k) {
        try {
            StringBuilder fs = new StringBuilder();
            for (Field f : k.getDeclaredFields()) fs.append(f.getType().getSimpleName()).append(' ').append(f.getName()).append("; ");
            System.out.println("[RECPROBE] " + tagName + " OK: " + fs + " isRecord=" + k.isRecord());
        } catch (Throwable t) { System.out.println("[RECPROBE] " + tagName + " FAILED:"); t.printStackTrace(System.out); }
    }
    static void construct(String tagName, Constructor<?> ctor, Object text, Object srcVal) {
        try { Object o = ctor.newInstance(7, text, null, srcVal, null); System.out.println("[RECPROBE] " + tagName + " OK: " + o); }
        catch (Throwable t) { System.out.println("[RECPROBE] " + tagName + " FAILED:");
            Throwable r = t; while (r.getCause() != null && r instanceof java.lang.reflect.InvocationTargetException) r = r.getCause();
            r.printStackTrace(System.out); }
    }
}

