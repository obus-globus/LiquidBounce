import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;

/**
 * recordprobe3.jar — bug #22 bisection, phase 2 (LB-free, bare MC). Bundles ASM 9.10.1.
 * agent arg selects the retransform payload for the already-loaded record GuiMessage:
 *   "asmrt"       — pure ASM round-trip of the incoming bytes with the converter's exact I/O flags
 *                   (ClassReader SKIP_FRAMES -> ClassNode -> ClassWriter COMPUTE_MAXS|COMPUTE_FRAMES).
 *                   No semantic change. Isolates "ASM re-serialization" as the corruption trigger.
 *   "conv=<path>" — the exact converted bytes the full-agent CFT returned in run 1 (dumped .class file).
 * After the retransform: construct (logged first) then getDeclaredFields (logged last; may hard-crash).
 * Same class can be re-attached with different args (class is defined once; agentmain re-runs).
 */
public class RecordProbe3 {
    static final String GM = "net.minecraft.client.multiplayer.chat.GuiMessage";

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        System.out.println("[RECPROBE3] start args=" + args);
        Class<?> gm = Class.forName(GM, true, sys);
        Class<?> comp = Class.forName("net.minecraft.network.chat.Component", true, sys);
        Class<?> sig = Class.forName("net.minecraft.network.chat.MessageSignature", false, sys);
        Class<?> src = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageSource", true, sys);
        Class<?> tag = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageTag", false, sys);
        Object text = comp.getMethod("literal", String.class).invoke(null, "recprobe3");
        Object sysClient = src.getField("SYSTEM_CLIENT").get(null);
        Constructor<?> ctor = gm.getDeclaredConstructor(int.class, comp, sig, src, tag);

        final String mode = args == null ? "" : args.trim();
        final byte[] convBytes = mode.startsWith("conv=") ? java.nio.file.Files.readAllBytes(java.nio.file.Path.of(mode.substring(5))) : null;

        ClassFileTransformer t = new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                if (n == null || !n.equals(GM.replace('.', '/'))) return null;
                try {
                    if (convBytes != null) { System.out.println("[RECPROBE3] returning conv bytes (" + convBytes.length + ")"); return convBytes; }
                    // asmrt: converter-identical I/O flags
                    org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader(b).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
                    org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(
                            org.objectweb.asm.ClassWriter.COMPUTE_MAXS | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
                    node.accept(w);
                    byte[] out = w.toByteArray();
                    System.out.println("[RECPROBE3] asm roundtrip " + b.length + " -> " + out.length + " bytes");
                    return out;
                } catch (Throwable x) { System.out.println("[RECPROBE3] transformer FAILED: " + x); x.printStackTrace(System.out); return null; }
            }
        };
        inst.addTransformer(t, true);
        try { inst.retransformClasses(gm); System.out.println("[RECPROBE3] retransform OK"); }
        catch (Throwable x) { System.out.println("[RECPROBE3] retransform FAILED:"); x.printStackTrace(System.out); }
        finally { inst.removeTransformer(t); }

        try { Object o = ctor.newInstance(9, text, null, sysClient, null); System.out.println("[RECPROBE3] post.construct OK: " + o); }
        catch (Throwable x) { System.out.println("[RECPROBE3] post.construct FAILED:");
            Throwable r = x; while (r.getCause() != null && r instanceof java.lang.reflect.InvocationTargetException) r = r.getCause();
            r.printStackTrace(System.out); }
        try {
            StringBuilder fs = new StringBuilder();
            for (Field f : gm.getDeclaredFields()) fs.append(f.getType().getSimpleName()).append(' ').append(f.getName()).append("; ");
            System.out.println("[RECPROBE3] post.reflect OK: " + fs + " isRecord=" + gm.isRecord());
        } catch (Throwable x) { System.out.println("[RECPROBE3] post.reflect FAILED:"); x.printStackTrace(System.out); }
        System.out.println("[RECPROBE3] done");
    }
}
