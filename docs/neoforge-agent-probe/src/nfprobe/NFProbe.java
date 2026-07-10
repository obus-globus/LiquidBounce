package nfprobe;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoForge agent probe — the cheapest kill-shot.
 *
 * Question: can a premain -javaagent transform net.minecraft.client.Minecraft on a LIVE
 * NeoForge install WITHOUT LiquidBounce being a discovered ModFile?
 *
 * The seam-map subagent claimed NO ("premain ClassFileTransformer can't hook Minecraft —
 * timing mismatch, FML's classloader not yet created"). That misreads the API:
 * Instrumentation.addTransformer(t, true) registers for the whole JVM lifetime and fires
 * for EVERY class defined by ANY classloader, including NeoForge's TransformingClassLoader
 * defining Minecraft much later. This probe tests that empirically.
 *
 * Uses JDK 25's built-in java.lang.classfile (JEP 484, final in JDK 24) — zero external ASM,
 * so there is no duplicate-ASM abort risk (which bit the Fabric agent).
 */
public final class NFProbe {

    static final String TARGET = "net/minecraft/client/Minecraft";
    static final ClassDesc CD_System = ClassDesc.of("java.lang.System");
    static final ClassDesc CD_PrintStream = ClassDesc.of("java.io.PrintStream");
    static final ClassDesc CD_String = ClassDesc.of("java.lang.String");
    static final MethodTypeDesc MTD_println = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), CD_String);
    static final AtomicBoolean firstClientSeen = new AtomicBoolean(false);

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[NFPROBE] premain fired — java=" + System.getProperty("java.version")
                + " retransformSupported=" + inst.isRetransformClassesSupported()
                + " redefineSupported=" + inst.isRedefineClassesSupported());
        inst.addTransformer(new T(), true);
        System.out.println("[NFPROBE] ClassFileTransformer registered (whole-JVM, all classloaders)");
    }

    static final class T implements ClassFileTransformer {
        // JVM calls this 6-arg overload (Module-aware) when available.
        @Override
        public byte[] transform(Module module, ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain pd, byte[] buf) {
            if (className == null) return null;

            // Prove we reach vanilla client classes on NeoForge's loader at all.
            if (className.startsWith("net/minecraft/client/") && firstClientSeen.compareAndSet(false, true)) {
                System.out.println("[NFPROBE] first net.minecraft.client class intercepted: " + className
                        + " loader=" + loaderName(loader) + " module=" + moduleName(module));
            }

            if (!TARGET.equals(className)) return null;

            System.out.println("[NFPROBE] *** transform() SAW " + className
                    + " loader=" + loaderName(loader) + " module=" + moduleName(module) + " ***");
            try {
                ClassFile cf = ClassFile.of();
                ClassModel cm = cf.parse(buf);
                ClassTransform ct = (cb, cle) -> {
                    if (cle instanceof MethodModel mm
                            && (mm.methodName().equalsString("<clinit>") || mm.methodName().equalsString("run"))) {
                        String mn = mm.methodName().stringValue();
                        cb.transformMethod(mm, MethodTransform.transformingCode(headPrintln(mn)));
                    } else {
                        cb.accept(cle);
                    }
                };
                byte[] out = cf.transformClass(cm, ct);
                System.out.println("[NFPROBE] transformed Minecraft (" + buf.length + " -> " + out.length + " bytes)");
                return out;
            } catch (Throwable t) {
                System.out.println("[NFPROBE] transform FAILED (observation still stands): " + t);
                return null;
            }
        }

        // legacy 5-arg overload delegates
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> cbr,
                                ProtectionDomain pd, byte[] buf) {
            return transform((Module) null, loader, className, cbr, pd, buf);
        }
    }

    static CodeTransform headPrintln(String method) {
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder cob) {
                cob.getstatic(CD_System, "out", CD_PrintStream)
                   .ldc("[NFPROBE-INJECTED] executing inside net.minecraft.client.Minecraft." + method
                           + " on a LIVE NeoForge install — injected by a premain agent, NOT a mod")
                   .invokevirtual(CD_PrintStream, "println", MTD_println);
                System.out.println("[NFPROBE] injected println into Minecraft." + method);
            }
            @Override
            public void accept(CodeBuilder cob, CodeElement e) {
                cob.accept(e);
            }
        };
    }

    static String loaderName(ClassLoader l) { return l == null ? "boot" : l.getClass().getName(); }
    static String moduleName(Module m) { return (m == null || m.getName() == null) ? "unnamed" : m.getName(); }
}
