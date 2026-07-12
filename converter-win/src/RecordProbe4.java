import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;

/**
 * recordprobe4.jar — generic redefinition-corruption probe (LB-free, bundles ASM 9.10.1).
 * args: "mode=<ident|asmrt|keepcp|conv>;cls=<dotted class>[;conv=<path to .class bytes>]"
 *   ident  — retransform with clone of incoming bytes (control; known clean)
 *   asmrt  — ClassNode round-trip, ClassWriter(COMPUTE_MAXS|COMPUTE_FRAMES) WITHOUT reader (CP rebuilt; known corrupting for GuiMessage)
 *   keepcp — same but ClassWriter(reader, flags) => constant-pool-preserving (the candidate converter fix)
 *   conv   — bytes from file
 * After retransform: for GuiMessage also constructs via canonical ctor; then getDeclaredFields/Methods (corruption detectors).
 * Re-attachable with different args (same class instance re-used).
 */
public class RecordProbe4 {
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        System.out.println("[RECPROBE4] start args=" + args);
        java.util.Map<String,String> a = new java.util.HashMap<>();
        for (String kv : args.split(";")) { int i = kv.indexOf('='); if (i > 0) a.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim()); }
        final String mode = a.getOrDefault("mode", "ident");
        final String clsName = a.getOrDefault("cls", "net.minecraft.client.multiplayer.chat.GuiMessage");
        final byte[] convBytes = a.containsKey("conv") ? java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a.get("conv"))) : null;
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        Class<?> k = Class.forName(clsName, true, sys);
        final String internal = clsName.replace('.', '/');

        ClassFileTransformer t = new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                if (n == null || !n.equals(internal)) return null;
                try {
                    switch (mode) {
                        case "ident": return b.clone();
                        case "conv": return convBytes;
                        case "asmrt": case "keepcp": {
                            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(b);
                            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                            cr.accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
                            int fl = org.objectweb.asm.ClassWriter.COMPUTE_MAXS | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
                            org.objectweb.asm.ClassWriter w = mode.equals("keepcp") ? new org.objectweb.asm.ClassWriter(cr, fl) : new org.objectweb.asm.ClassWriter(fl);
                            node.accept(w);
                            byte[] out = w.toByteArray();
                            System.out.println("[RECPROBE4] " + mode + " " + b.length + " -> " + out.length);
                            return out;
                        }
                        default: return null;
                    }
                } catch (Throwable x) { System.out.println("[RECPROBE4] transformer FAILED: " + x); return null; }
            }
        };
        inst.addTransformer(t, true);
        try { inst.retransformClasses(k); System.out.println("[RECPROBE4] retransform(" + clsName + ", " + mode + ") OK"); }
        catch (Throwable x) { System.out.println("[RECPROBE4] retransform FAILED:"); x.printStackTrace(System.out); }
        finally { inst.removeTransformer(t); }

        if (clsName.endsWith(".GuiMessage")) {
            try {
                Class<?> comp = Class.forName("net.minecraft.network.chat.Component", true, sys);
                Class<?> sig = Class.forName("net.minecraft.network.chat.MessageSignature", false, sys);
                Class<?> src = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageSource", true, sys);
                Class<?> tag = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageTag", false, sys);
                Object text = comp.getMethod("literal", String.class).invoke(null, "rp4");
                Object sysClient = src.getField("SYSTEM_CLIENT").get(null);
                Constructor<?> ctor = k.getDeclaredConstructor(int.class, comp, sig, src, tag);
                Object o = ctor.newInstance(4, text, null, sysClient, null);
                System.out.println("[RECPROBE4] post.construct OK: " + o);
            } catch (Throwable x) { System.out.println("[RECPROBE4] post.construct FAILED:");
                Throwable r = x; while (r.getCause() != null && r instanceof java.lang.reflect.InvocationTargetException) r = r.getCause();
                r.printStackTrace(System.out); }
        }
        try {
            StringBuilder fs = new StringBuilder();
            for (Field f : k.getDeclaredFields()) fs.append(f.getName()).append(' ');
            System.out.println("[RECPROBE4] post.reflectFields OK: " + fs);
        } catch (Throwable x) { System.out.println("[RECPROBE4] post.reflectFields FAILED: " + x); }
        try { int m = k.getDeclaredMethods().length; System.out.println("[RECPROBE4] post.reflectMethods OK: " + m); }
        catch (Throwable x) { System.out.println("[RECPROBE4] post.reflectMethods FAILED: " + x); }
        System.out.println("[RECPROBE4] done (" + clsName + ", " + mode + ")");
    }
}
