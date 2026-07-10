package nfagent;

import java.io.File;
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
import java.lang.reflect.Method;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full LiquidBounce-on-NeoForge interposer — a premain agent that loads LB WITHOUT LB being a mod.
 * Mirrors the Fabric interposer, mapped to FML 11 seams (all verified by javap + the probe):
 *   1. Install a child-first fallback loader on the TransformingClassLoader so LB's classes +
 *      resources resolve and still see net.minecraft.* (NeoForge analogue of addToClassPath(Knot)).
 *   2. Load LB's AccessTransformer into FML's live AT engine (hook AccessTransformerService.<init>)
 *      so NeoForge applies it natively — Mixin metadata sees the widened members.
 *   3. Register LB's real mixin configs into the live FMLMixinService (hook
 *      MixinFacade.finishInitialization) — the DEFER path proven by the probe.
 * LB then bootstraps exactly as on Fabric: MixinMinecraft fires ClientStartEvent.
 *
 * Uses JDK 25 java.lang.classfile (no external ASM).
 */
public final class NFAgent {

    static final String TCL = "net.neoforged.fml.classloading.transformation.TransformingClassLoader";
    static final String TARGET_AT = "net/neoforged/fml/common/asm/AccessTransformerService";
    static final String TARGET_FACADE = "net/neoforged/fml/loading/mixin/MixinFacade";
    static final String TARGET_NFPLATFORM = "net/ccbluex/liquidbounce/platform/neoforge/NeoForgePlatform";

    static final ClassDesc CD_System = ClassDesc.of("java.lang.System");
    static final ClassDesc CD_PrintStream = ClassDesc.of("java.io.PrintStream");
    static final ClassDesc CD_String = ClassDesc.of("java.lang.String");
    static final ClassDesc CD_void = ClassDesc.ofDescriptor("V");
    static final ClassDesc CD_byteArr = ClassDesc.ofDescriptor("[B");
    static final ClassDesc CD_InputStream = ClassDesc.of("java.io.InputStream");
    static final ClassDesc CD_ClassLoader = ClassDesc.of("java.lang.ClassLoader");
    static final ClassDesc CD_Reader = ClassDesc.of("java.io.Reader");
    static final ClassDesc CD_FileReader = ClassDesc.of("java.io.FileReader");
    static final ClassDesc CD_ATEngine = ClassDesc.of("net.neoforged.accesstransformer.api.AccessTransformerEngine");
    static final ClassDesc CD_MixinService = ClassDesc.of("org.spongepowered.asm.service.MixinService");
    static final ClassDesc CD_IMixinService = ClassDesc.of("org.spongepowered.asm.service.IMixinService");
    static final ClassDesc CD_FMLMixinService = ClassDesc.of("net.neoforged.fml.loading.mixin.FMLMixinService");
    static final ClassDesc CD_Mixins = ClassDesc.of("org.spongepowered.asm.mixin.Mixins");

    static final MethodTypeDesc MTD_println = MethodTypeDesc.of(CD_void, CD_String);
    static final MethodTypeDesc MTD_getProperty = MethodTypeDesc.of(CD_String, CD_String);
    static final MethodTypeDesc MTD_frInit = MethodTypeDesc.of(CD_void, CD_String);
    static final MethodTypeDesc MTD_loadAT = MethodTypeDesc.of(CD_void, CD_Reader, CD_String);
    static final MethodTypeDesc MTD_getService = MethodTypeDesc.of(CD_IMixinService);
    static final MethodTypeDesc MTD_getResourceAsStream = MethodTypeDesc.of(CD_InputStream, CD_String);
    static final MethodTypeDesc MTD_readAllBytes = MethodTypeDesc.of(CD_byteArr);
    static final MethodTypeDesc MTD_addContent = MethodTypeDesc.of(CD_void, CD_String, CD_byteArr);
    static final MethodTypeDesc MTD_addConfiguration = MethodTypeDesc.of(CD_void, CD_String);

    static final String[] LB_CONFIGS = { "liquidbounce.mixins.json", "liquidbounce-neoforge.mixins.json" };

    static volatile URL[] LB_URLS;
    static final AtomicBoolean fallbackSet = new AtomicBoolean(false);

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[NFAGENT] premain — building LB fallback URLs");
        List<URL> urls = new ArrayList<>();
        addPaths(urls, System.getProperty("lb.buildDirs", ""));
        addPaths(urls, System.getProperty("lb.libs", ""));
        LB_URLS = urls.toArray(new URL[0]);
        System.out.println("[NFAGENT] LB fallback has " + LB_URLS.length + " entries; AT=" + System.getProperty("lb.at"));
        inst.addTransformer(new T(), true);
    }

    /** Reflectively read a (possibly inherited, private) ClassLoader field. */
    static ClassLoader readField(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (ClassLoader) f.get(obj);
            } catch (NoSuchFieldException nsf) { /* walk up */ }
            catch (Throwable t) { System.out.println("[NFAGENT] readField " + name + " failed: " + t); return null; }
        }
        return null;
    }

    static void addPaths(List<URL> urls, String cp) {
        if (cp == null || cp.isEmpty()) return;
        for (String p : cp.split(java.io.File.pathSeparator)) {
            if (p.isEmpty()) continue;
            try { urls.add(new File(p).toURI().toURL()); } catch (Exception e) { System.out.println("[NFAGENT] bad path " + p + ": " + e); }
        }
    }

    static final class T implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String name,
                                Class<?> red, ProtectionDomain pd, byte[] buf) {
            if (name == null) return null;

            // (1) install the LB fallback on the TransformingClassLoader, once, as early as we see it.
            if (loader != null && TCL.equals(loader.getClass().getName()) && fallbackSet.compareAndSet(false, true)) {
                try {
                    ClassLoader original = readField(loader, "fallbackClassLoader");
                    ClassLoader lb = new LbLoader(LB_URLS, loader, original);
                    Method m = loader.getClass().getMethod("setFallbackClassLoader", ClassLoader.class);
                    m.invoke(loader, lb);
                    System.out.println("[NFAGENT] installed LB child-first fallback (chained to original="
                            + (original == null ? "null" : original.getClass().getName()) + ")");
                } catch (Throwable t) {
                    System.out.println("[NFAGENT] fallback install FAILED: " + t);
                }
            }

            if (TARGET_AT.equals(name)) return hookAt(buf);
            if (TARGET_FACADE.equals(name)) return hookFacade(buf);
            if (TARGET_NFPLATFORM.equals(name)) return hookPlatform(buf);
            if ("net/neoforged/fml/startup/FatalErrorReporting".equals(name)) return hookFatal(buf);
            return null;
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> red, ProtectionDomain pd, byte[] buf) {
            return transform((Module) null, loader, name, red, pd, buf);
        }
    }

    /** Hook AccessTransformerService.<init>(engine): at end, engine.loadAT(new FileReader(lb.at), "liquidbounce"). */
    static byte[] hookAt(byte[] buf) {
        try {
            ClassFile cf = ClassFile.of();
            ClassModel cm = cf.parse(buf);
            ClassTransform ct = (cb, cle) -> {
                if (cle instanceof MethodModel mm && mm.methodName().equalsString("<init>")) {
                    cb.transformMethod(mm, MethodTransform.transformingCode(new CodeTransform() {
                        @Override public void atEnd(CodeBuilder cob) {
                            cob.aload(1) // AccessTransformerEngine engine (constructor param 1)
                               .new_(CD_FileReader).dup()
                               .ldc("lb.at").invokestatic(CD_System, "getProperty", MTD_getProperty, false)
                               .invokespecial(CD_FileReader, "<init>", MTD_frInit)
                               .ldc("liquidbounce")
                               .invokeinterface(CD_ATEngine, "loadAT", MTD_loadAT);
                            cob.getstatic(CD_System, "out", CD_PrintStream)
                               .ldc("[NFAGENT] loaded LB AccessTransformer into FML AT engine")
                               .invokevirtual(CD_PrintStream, "println", MTD_println);
                        }
                        @Override public void accept(CodeBuilder cob, CodeElement e) { cob.accept(e); }
                    }));
                } else cb.accept(cle);
            };
            byte[] out = cf.transformClass(cm, ct);
            System.out.println("[NFAGENT] hooked AccessTransformerService.<init> (" + buf.length + " -> " + out.length + ")");
            return out;
        } catch (Throwable t) { System.out.println("[NFAGENT] AT hook FAILED: " + t); return null; }
    }

    /** Hook MixinFacade.finishInitialization(modList, tcl): at head, register LB's configs into the live service. */
    static byte[] hookFacade(byte[] buf) {
        try {
            ClassFile cf = ClassFile.of();
            ClassModel cm = cf.parse(buf);
            ClassTransform ct = (cb, cle) -> {
                if (cle instanceof MethodModel mm && mm.methodName().equalsString("finishInitialization")) {
                    cb.transformMethod(mm, MethodTransform.transformingCode(new CodeTransform() {
                        @Override public void atStart(CodeBuilder cob) {
                            for (String cfg : LB_CONFIGS) emitRegister(cob, cfg);
                            cob.getstatic(CD_System, "out", CD_PrintStream)
                               .ldc("[NFAGENT] registered LB mixin configs into live FMLMixinService")
                               .invokevirtual(CD_PrintStream, "println", MTD_println);
                        }
                        @Override public void accept(CodeBuilder cob, CodeElement e) { cob.accept(e); }
                    }));
                } else cb.accept(cle);
            };
            byte[] out = cf.transformClass(cm, ct);
            System.out.println("[NFAGENT] hooked MixinFacade.finishInitialization (" + buf.length + " -> " + out.length + ")");
            return out;
        } catch (Throwable t) { System.out.println("[NFAGENT] MixinFacade hook FAILED: " + t); return null; }
    }

    /**
     * Root fix for the no-mod-bus init gap. NeoForgePlatform.registerResourceReloadListeners returns
     * true whenever the listener ids are known — WITHOUT checking that the @Mod mod-bus
     * AddClientReloadListenersEvent actually added the LazyReloadListener wrappers. With no ModFile
     * that event never fires, so no wrapper is in the resource manager, yet the method still returns
     * true and LB's designed direct-reload fallback (initializeClient + theme reload) never runs —
     * modules never register (KillAura circular-init crash) and the MCEF menu never loads. We force
     * it to return false so LB takes its own intended fallback path, which runs registerInbuilt() on
     * the render thread during ClientStartEvent (before any render frame) and reloads the theme.
     */
    static byte[] hookPlatform(byte[] buf) {
        try {
            ClassFile cf = ClassFile.of();
            ClassModel cm = cf.parse(buf);
            byte[] out = cf.build(cm.thisClass().asSymbol(), cb -> {
                for (var e : cm) {
                    if (e instanceof MethodModel mm && mm.methodName().equalsString("registerResourceReloadListeners")) {
                        cb.withMethod(mm.methodName().stringValue(), mm.methodTypeSymbol(),
                                mm.flags().flagsMask(), mb -> mb.withCode(cob -> cob.iconst_0().ireturn()));
                    } else {
                        cb.with(e);
                    }
                }
            });
            System.out.println("[NFAGENT] forced NeoForgePlatform.registerResourceReloadListeners=false (LB runs its no-mod-bus init fallback)");
            return out;
        } catch (Throwable t) { System.out.println("[NFAGENT] platform hook FAILED: " + t); return null; }
    }

    /** Diagnostic: print the fatal Throwable to console (the GL error screen hangs under software GL). */
    static byte[] hookFatal(byte[] buf) {
        try {
            ClassFile cf = ClassFile.of();
            ClassModel cm = cf.parse(buf);
            MethodTypeDesc sig = MethodTypeDesc.of(CD_void, ClassDesc.of("java.lang.Throwable"));
            ClassTransform ct = (cb, cle) -> {
                if (cle instanceof MethodModel mm && mm.methodName().equalsString("reportFatalError")
                        && mm.methodTypeSymbol().equals(sig)) {
                    cb.transformMethod(mm, MethodTransform.transformingCode(new CodeTransform() {
                        @Override public void atStart(CodeBuilder cob) {
                            cob.getstatic(CD_System, "out", CD_PrintStream)
                               .ldc("[NFAGENT] ===== FATAL (printed by agent) =====")
                               .invokevirtual(CD_PrintStream, "println", MTD_println);
                            cob.aload(0).invokevirtual(ClassDesc.of("java.lang.Throwable"), "printStackTrace",
                                    MethodTypeDesc.of(CD_void));
                        }
                        @Override public void accept(CodeBuilder cob, CodeElement e) { cob.accept(e); }
                    }));
                } else cb.accept(cle);
            };
            byte[] out = cf.transformClass(cm, ct);
            System.out.println("[NFAGENT] hooked FatalErrorReporting.reportFatalError (diagnostic)");
            return out;
        } catch (Throwable t) { System.out.println("[NFAGENT] fatal hook FAILED: " + t); return null; }
    }

    /** ((FMLMixinService)MixinService.getService()).addMixinConfigContent(cfg, tcl.getResourceAsStream(cfg).readAllBytes()); Mixins.addConfiguration(cfg); */
    static void emitRegister(CodeBuilder cob, String cfg) {
        cob.invokestatic(CD_MixinService, "getService", MTD_getService, false)
           .checkcast(CD_FMLMixinService)
           .ldc(cfg)
           .aload(2) // TransformingClassLoader param
           .ldc(cfg)
           .invokevirtual(CD_ClassLoader, "getResourceAsStream", MTD_getResourceAsStream)
           .invokevirtual(CD_InputStream, "readAllBytes", MTD_readAllBytes)
           .invokevirtual(CD_FMLMixinService, "addMixinConfigContent", MTD_addContent);
        cob.ldc(cfg)
           .invokestatic(CD_Mixins, "addConfiguration", MTD_addConfiguration, false);
    }
}
