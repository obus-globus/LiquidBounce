import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

/**
 * bug22 standalone repro — retransforms the ALREADY-LOADED vanilla GuiMessage record with a candidate byte
 * variant, then hammers the exact paths that fail in-game:
 *   - Class.getDeclaredFields() (crashed the JVM in run1: EXCEPTION_ACCESS_VIOLATION in getDeclaredFields0)
 *   - Class.getDeclaredField("addedTime") / getRecordComponents()
 *   - canonical <init> (putfield addedTime resolution = the NoSuchFieldError site)
 * with System.gc() cycles interleaved so the OLD class version's metadata gets purged (the suspected
 * freed-memory read). Run on the SAME JVM as MC (GraalVM CE 25) with the MC classpath.
 *
 * usage: java -javaagent:repro.jar -cp <mc-cp>;<pa.jar>;. Repro <variant.class> [iters]
 */
public class Repro {
    static Instrumentation INST;
    public static void premain(String a, Instrumentation i) { INST = i; }
    static final String GM = "net.minecraft.client.multiplayer.chat.GuiMessage";
    static final String GMI = GM.replace('.', '/');

    public static void main(String[] args) throws Exception {
        byte[] variant = Files.readAllBytes(Path.of(args[0]));
        int iters = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        System.out.println("[REPRO] variant=" + args[0] + " bytes=" + variant.length + " iters=" + iters);

        // allow ClassLoader.defineClass reflection (same trick as FullInjectAgent)
        INST.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
            Map.of("java.lang", Set.of(Repro.class.getModule())), Set.of(), Map.of());

        Class<?> gm = Class.forName(GM, false, ClassLoader.getSystemClassLoader());
        defineSidecarStubs(gm.getProtectionDomain());   // MC jar is signed -> must match the package's signer
        Constructor<?> ctor = gm.getDeclaredConstructor(int.class,
            Class.forName("net.minecraft.network.chat.Component"),
            Class.forName("net.minecraft.network.chat.MessageSignature"),
            Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageSource"),
            Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageTag"));
        Object ok = ctor.newInstance(7, null, null, null, null);
        System.out.println("[REPRO] pre-retransform construct OK: " + ok);

        final byte[] vb = variant;
        INST.addTransformer(new ClassFileTransformer() {
            public byte[] transform(Module md, ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                return (c != null && GMI.equals(n)) ? vb : null;
            }
        }, true);
        INST.retransformClasses(gm);
        System.out.println("[REPRO] retransform OK");

        for (int i = 0; i < iters; i++) {
            if (i % 250 == 0) { System.gc(); System.gc(); }
            try {
                Field[] fs = gm.getDeclaredFields();
                if (fs.length != 6) { System.out.println("[REPRO] FIELD-COUNT " + fs.length + " at iter " + i); return; }
                gm.getDeclaredField("addedTime");
                Object[] rc = gm.getRecordComponents();
                if (rc == null || rc.length != 5) { System.out.println("[REPRO] RC-COUNT " + (rc == null ? -1 : rc.length) + " at iter " + i); return; }
                ctor.newInstance(i, null, null, null, null);
            } catch (Throwable t) {
                System.out.println("[REPRO] FAIL at iter " + i + " -> " + deep(t));
                deepT(t).printStackTrace(System.out);
                return;
            }
        }
        System.out.println("[REPRO] PASS: " + iters + " iterations clean (fields/record/ctor all good)");
        // corruption detector: re-retransform with the IDENTICAL bytes. On healthy metadata the VM accepts it;
        // corrupted record metadata makes the compare crash or throw a spurious "changed Record attribute".
        try { INST.retransformClasses(gm); System.out.println("[REPRO] detector retransform OK (metadata healthy)"); }
        catch (Throwable t) { System.out.println("[REPRO] DETECTOR-FAIL -> " + deep(t)); }
    }

    static Throwable deepT(Throwable t) { while (t.getCause() != null) t = t.getCause(); return t; }
    static String deep(Throwable t) { t = deepT(t); return t.getClass().getName() + ": " + t.getMessage(); }

    /** define no-op GuiMessage$$LBSidecar (+$State) so the converted <init>'s INVOKESTATICs link. */
    static void defineSidecarStubs(ProtectionDomain pd) throws Exception {
        org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0);
        String sc = GMI + "$$LBSidecar";
        w.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, sc, null, "java/lang/Object", null);
        for (String[] m : new String[][]{
                {"set$liquid_bounce$id", "(L" + GMI + ";Ljava/lang/String;)V"},
                {"set$liquid_bounce$count", "(L" + GMI + ";I)V"}}) {
            var mv = w.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, m[0], m[1], null, null);
            mv.visitCode(); mv.visitInsn(org.objectweb.asm.Opcodes.RETURN); mv.visitMaxs(0, 3); mv.visitEnd();
        }
        w.visitEnd();
        byte[] b = w.toByteArray();
        var def = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
        def.setAccessible(true);
        def.invoke(ClassLoader.getSystemClassLoader(), sc.replace('/', '.'), b, 0, b.length, pd);
        System.out.println("[REPRO] sidecar stub defined");
    }
}
