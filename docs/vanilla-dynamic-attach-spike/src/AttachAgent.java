import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dynamic-attach feasibility agent. Loaded via VirtualMachine.attach(pid).loadAgent(jar, mode) into a
 * vanilla MC started with NO -javaagent. Two modes:
 *   caseA  — MC already at menu: retransform a LOADED MC class (a) body-only, (b) schema-changing.
 *   caseB:<internal/Name> — attach early at boot: register a ClassFileTransformer and transform the
 *            named MC class AS IT LOADS (no retransform), proving the early-attach window is reachable.
 */
public class AttachAgent {
    public static void agentmain(String args, Instrumentation inst) {
        System.out.println("[ATTACH] agentmain args=" + args
                + " retransformSupported=" + inst.isRetransformClassesSupported()
                + " redefineSupported=" + inst.isRedefineClassesSupported());
        try {
            if (args != null && args.startsWith("caseA")) {
                String tgt = args.contains(":") ? args.substring(args.indexOf(':') + 1).trim() : "net.minecraft.client.Minecraft";
                caseA(inst, tgt);
            }
            else if (args != null && args.startsWith("caseB")) caseB(inst, args.substring(args.indexOf(':') + 1).trim());
        } catch (Throwable t) { System.out.println("[ATTACH] agentmain error: " + t); t.printStackTrace(); }
    }

    // ---- Case A: retransform a class whose bytes are already loaded ----
    static void caseA(Instrumentation inst, String dotted) {
        Class<?> target = null;
        for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(dotted)) { target = c; break; }
        System.out.println("[ATTACH][A] target " + dotted + " loaded=" + (target != null));
        if (target == null) { System.out.println("[ATTACH][A] target not loaded; cannot test"); return; }
        final Class<?> t = target;
        final String internal = dotted.replace('.', '/');

        // (a) body-only: inject a println into method bodies, add NO members
        ClassFileTransformer body = new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                return internal.equals(n) ? rewrite(b, false) : null;
            }
        };
        inst.addTransformer(body, true);
        try { inst.retransformClasses(t); System.out.println("[ATTACH][A] (a) BODY-ONLY retransform: SUCCESS"); }
        catch (Throwable e) { System.out.println("[ATTACH][A] (a) BODY-ONLY retransform: FAILED -> " + rootType(e) + ": " + rootMsg(e)); }
        finally { inst.removeTransformer(body); }

        // (b) schema change: add a brand-new method
        ClassFileTransformer schema = new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                return internal.equals(n) ? rewrite(b, true) : null;
            }
        };
        inst.addTransformer(schema, true);
        try { inst.retransformClasses(t); System.out.println("[ATTACH][A] (b) SCHEMA-CHANGE retransform: SUCCESS (unexpected!)"); }
        catch (Throwable e) { System.out.println("[ATTACH][A] (b) SCHEMA-CHANGE retransform: FAILED -> " + rootType(e) + ": " + rootMsg(e)); }
        finally { inst.removeTransformer(schema); }
    }

    // ---- Case B: register a transformer, catch the named class as it loads ----
    static void caseB(Instrumentation inst, String internal) {
        System.out.println("[ATTACH][B] registering transformer for " + internal + " (was it already loaded? "
                + isLoaded(inst, internal.replace('/', '.')) + ")");
        AtomicBoolean hit = new AtomicBoolean(false);
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                if (!internal.equals(n)) return null;
                hit.set(true);
                byte[] out = rewrite(b, true); // on-load: even schema changes are allowed (initial definition)
                System.out.println("[ATTACH][B] >>> transformed " + n + " AS IT LOADED (" + b.length + " -> "
                        + (out != null ? out.length : b.length) + " bytes, schema change accepted on-load) <<<");
                return out;
            }
        }, true);
        // If the class is ALREADY loaded, we lost the race; report it.
        if (isLoaded(inst, internal.replace('/', '.')))
            System.out.println("[ATTACH][B] LOST THE RACE: " + internal + " already loaded before attach");
        else
            System.out.println("[ATTACH][B] transformer armed before " + internal + " loaded — waiting for it to load");
    }

    static boolean isLoaded(Instrumentation inst, String dotted) {
        for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(dotted)) return true;
        return false;
    }

    /** Rewrite: inject a marker println into the first method with code (body-only); if addMethod, also add
     *  a new static method (schema change). Returns valid classfile bytes. */
    static byte[] rewrite(byte[] buf, boolean addMethod) {
        try {
            ClassFile cf = ClassFile.of();
            ClassModel cm = cf.parse(buf);
            final boolean[] injected = {false};
            byte[] out = cf.build(cm.thisClass().asSymbol(), clb -> {
                for (var e : cm) {
                    if (!injected[0] && e instanceof MethodModel mm && mm.code().isPresent()
                            && !mm.methodName().equalsString("<init>") && !mm.methodName().equalsString("<clinit>")) {
                        injected[0] = true;
                        clb.withMethod(mm.methodName().stringValue(), mm.methodTypeSymbol(), mm.flags().flagsMask(), mb ->
                            mb.withCode(cob -> {
                                cob.getstatic(ClassDesc.of("java.lang.System"), "out", ClassDesc.of("java.io.PrintStream"))
                                   .ldc("[ATTACH] injected-marker executed in " + cm.thisClass().asInternalName())
                                   .invokevirtual(ClassDesc.of("java.io.PrintStream"), "println", MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), ClassDesc.of("java.lang.String")));
                                mm.code().get().forEach(cob); // original body follows
                            }));
                    } else {
                        clb.with(e);
                    }
                }
                if (addMethod) {
                    clb.withMethodBody("__attachProbe__", MethodTypeDesc.of(ClassDesc.ofDescriptor("V")),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> cob.return_());
                }
            });
            return out;
        } catch (Throwable t) { System.out.println("[ATTACH] rewrite failed: " + t); return null; }
    }

    static String rootType(Throwable t) { Throwable c = t; while (c.getCause() != null) c = c.getCause(); return c.getClass().getName(); }
    static String rootMsg(Throwable t) { Throwable c = t; while (c.getCause() != null) c = c.getCause(); return String.valueOf(c.getMessage()); }
}
