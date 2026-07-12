import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.security.ProtectionDomain;

/**
 * recordprobe5.jar — capture the EXACT construction failure (type+message only, no stack print, no reflection —
 * both can hard-crash the VM once the metadata is corrupted) after a plain (CP-reordering) ASM round-trip
 * retransform of GuiMessage. Documents the doc-#22 symptom string verbatim. LB-free; bundles ASM.
 */
public class RecordProbe5 {
    static final String GM = "net.minecraft.client.multiplayer.chat.GuiMessage";
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        Class<?> gm = Class.forName(GM, true, sys);
        Class<?> comp = Class.forName("net.minecraft.network.chat.Component", true, sys);
        Class<?> sig = Class.forName("net.minecraft.network.chat.MessageSignature", false, sys);
        Class<?> src = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageSource", true, sys);
        Class<?> tag = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageTag", false, sys);
        Object text = comp.getMethod("literal", String.class).invoke(null, "rp5");
        Object sysClient = src.getField("SYSTEM_CLIENT").get(null);
        Constructor<?> ctor = gm.getDeclaredConstructor(int.class, comp, sig, src, tag);
        try { System.out.println("[RECPROBE5] pre.construct OK: " + ctor.newInstance(5, text, null, sysClient, null)); }
        catch (Throwable t) { System.out.println("[RECPROBE5] pre.construct threw " + describe(t)); }
        ClassFileTransformer t = new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                if (n == null || !n.equals(GM.replace('.', '/'))) return null;
                try {
                    org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader(b).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
                    org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(
                            org.objectweb.asm.ClassWriter.COMPUTE_MAXS | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
                    node.accept(w); return w.toByteArray();
                } catch (Throwable x) { return null; }
            }
        };
        inst.addTransformer(t, true);
        try { inst.retransformClasses(gm); System.out.println("[RECPROBE5] asmrt retransform OK"); }
        finally { inst.removeTransformer(t); }
        try { System.out.println("[RECPROBE5] post.construct OK: " + ctor.newInstance(5, text, null, sysClient, null)); }
        catch (Throwable x) { System.out.println("[RECPROBE5] post.construct threw " + describe(x)); }
        System.out.println("[RECPROBE5] done");
    }
    static String describe(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
        return t.getClass().getName() + ": " + t.getMessage();
    }
}
