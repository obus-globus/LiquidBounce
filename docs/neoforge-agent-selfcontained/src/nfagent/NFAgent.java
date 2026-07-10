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
    static final String TARGET_RELOAD_EVENT = "net/neoforged/neoforge/client/event/AddClientReloadListenersEvent";
    static final ClassDesc CD_NeoForgePlatform = ClassDesc.of("net.ccbluex.liquidbounce.platform.neoforge.NeoForgePlatform");
    static final ClassDesc CD_AddClientReloadEvent = ClassDesc.of("net.neoforged.neoforge.client.event.AddClientReloadListenersEvent");

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
    static volatile java.util.Set<String> OWNED_PKGS = java.util.Collections.emptySet();
    static final AtomicBoolean fallbackSet = new AtomicBoolean(false);

    /**
     * Self-contained premain: stage the bundled payload out of THIS agent jar (agent-libs/*.jar +
     * accesstransformer.cfg at the root) into a temp dir — no -Dlb.* dev paths, no external staging.
     * The fallback-loader OWNED package set is computed data-driven from the bundled jars.
     */
    public static void premain(String args, Instrumentation inst) {
        try {
            File agentJar = new File(NFAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // Optional offline MCEF (-PbundleMcefNative): stage bundled native + set PROVIDED_JCEF_PATH.
            McefNative.stageIfBundled(agentJar, "[NFAGENT]");
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("lb-nf-agent-");
            tmp.toFile().deleteOnExit();
            List<URL> urls = new ArrayList<>();
            java.util.Set<String> owned = new java.util.HashSet<>();
            java.util.Map<String,byte[]> rootRes = new java.util.HashMap<>(); // root-level LB resources (mixin config JSONs)
            java.nio.file.Path atFile = null;
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(agentJar)) {
                for (java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                    java.util.jar.JarEntry e = en.nextElement();
                    String n = e.getName();
                    if (n.equals("accesstransformer.cfg")) {
                        atFile = tmp.resolve("accesstransformer.cfg");
                        try (java.io.InputStream in = jf.getInputStream(e)) {
                            java.nio.file.Files.copy(in, atFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        atFile.toFile().deleteOnExit();
                        continue;
                    }
                    if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
                    java.nio.file.Path out = tmp.resolve(new File(n).getName());
                    try (java.io.InputStream in = jf.getInputStream(e)) {
                        java.nio.file.Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    out.toFile().deleteOnExit();
                    urls.add(out.toUri().toURL());
                    scanPackages(out, owned, rootRes);
                }
            }
            LB_URLS = urls.toArray(new URL[0]);
            OWNED_PKGS = owned;
            // the AT hook reads System.getProperty("lb.at"); point it at the runtime-extracted temp (not a dev path)
            if (atFile != null) System.setProperty("lb.at", atFile.toString());
            // Root-level LB resources (the mixin config JSONs) live at the LB jar root and would otherwise be
            // invisible now that LB is off the TransformingClassLoader's search. Repack them into a resources-ONLY
            // jar (no classes -> no class-loader split) and append to the SYSTEM loader; the MixinFacade hook then
            // reads each config via ClassLoader.getSystemResourceAsStream(cfg).
            if (!rootRes.isEmpty()) {
                java.nio.file.Path cfgJar = tmp.resolve("lb-agent-cfgs.jar");
                try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(cfgJar))) {
                    for (java.util.Map.Entry<String,byte[]> r : rootRes.entrySet()) {
                        jos.putNextEntry(new java.util.jar.JarEntry(r.getKey()));
                        jos.write(r.getValue());
                        jos.closeEntry();
                    }
                }
                cfgJar.toFile().deleteOnExit();
                inst.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(cfgJar.toFile()));
                System.out.println("[NFAGENT] appended " + rootRes.size() + " root LB resources to system loader: " + rootRes.keySet());
            }
            System.out.println("[NFAGENT] self-contained: staged " + LB_URLS.length + " bundled libs, "
                + OWNED_PKGS.size() + " owned packages (data-driven), AT=" + (atFile != null));
            inst.addTransformer(new T(), true);
        } catch (Throwable t) {
            System.out.println("[NFAGENT] premain FAILED: " + t); t.printStackTrace();
        }
    }

    /** Data-driven OWNED: record the package dir of every class in a bundled jar (child-first set),
     *  and collect root-level resource files (e.g. the mixin config JSONs) into rootRes for the system loader. */
    static void scanPackages(java.nio.file.Path jar, java.util.Set<String> owned, java.util.Map<String,byte[]> rootRes) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            for (java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                java.util.jar.JarEntry e = en.nextElement();
                String n = e.getName();
                if (n.endsWith(".class")) {
                    int i = n.lastIndexOf('/');
                    if (i > 0) owned.add(n.substring(0, i + 1)); // e.g. "net/ccbluex/liquidbounce/features/"
                } else if (!e.isDirectory() && n.indexOf('/') < 0 && !rootRes.containsKey(n)) {
                    try (java.io.InputStream in = jf.getInputStream(e)) { rootRes.put(n, in.readAllBytes()); }
                }
            }
        } catch (Exception ignored) { }
    }

    /** Register every LB-owned package into the TransformingClassLoader's `parentLoaders` map -> LbLoader,
     *  so the mixin transformer's byte reader (getMaybeTransformedClassBytes) can locate LB class bytes. */
    @SuppressWarnings("unchecked")
    static void registerParentLoaders(ClassLoader tcl, ClassLoader lb) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = tcl.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField("parentLoaders"); } catch (NoSuchFieldException ignore) { }
            }
            if (f == null) { System.out.println("[NFAGENT] parentLoaders field not found"); return; }
            f.setAccessible(true);
            java.util.Map<String,ClassLoader> cur = (java.util.Map<String,ClassLoader>) f.get(tcl);
            java.util.Map<String,ClassLoader> map = cur;
            boolean replace = false;
            try { map.put("__lb_probe__", lb); map.remove("__lb_probe__"); }
            catch (UnsupportedOperationException uoe) { map = new java.util.HashMap<>(cur); replace = true; }
            int n = 0;
            for (String pkg : OWNED_PKGS) {
                String dotted = (pkg.endsWith("/") ? pkg.substring(0, pkg.length() - 1) : pkg).replace('/', '.');
                if (map.putIfAbsent(dotted, lb) == null) n++;
            }
            if (replace) f.set(tcl, map);
            System.out.println("[NFAGENT] registered " + n + " LB packages into TCL.parentLoaders (mixin byte source)");
        } catch (Throwable t) {
            System.out.println("[NFAGENT] parentLoaders register FAILED: " + t); t.printStackTrace();
        }
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

    static final class T implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String name,
                                Class<?> red, ProtectionDomain pd, byte[] buf) {
            if (name == null) return null;

            // (1) install the LB fallback on the TransformingClassLoader, once, as early as we see it.
            if (loader != null && TCL.equals(loader.getClass().getName()) && fallbackSet.compareAndSet(false, true)) {
                try {
                    ClassLoader original = readField(loader, "fallbackClassLoader");
                    ClassLoader lb = new LbLoader(LB_URLS, loader, original, OWNED_PKGS);
                    Method m = loader.getClass().getMethod("setFallbackClassLoader", ClassLoader.class);
                    m.invoke(loader, lb);
                    System.out.println("[NFAGENT] installed LB child-first fallback (chained to original="
                            + (original == null ? "null" : original.getClass().getName()) + ")");
                    // The mixin transformer READS class bytes via ModuleClassLoader.getMaybeTransformedClassBytes,
                    // which resolves by the `parentLoaders` (package->loader) map — NOT the fallback loader. Register
                    // every LB package to LbLoader so mixin/companion class bytes are locatable during transformation.
                    registerParentLoaders(loader, lb);
                } catch (Throwable t) {
                    System.out.println("[NFAGENT] fallback install FAILED: " + t);
                }
            }

            if (TARGET_AT.equals(name)) return hookAt(buf);
            if (TARGET_FACADE.equals(name)) return hookFacade(buf);
            if (TARGET_RELOAD_EVENT.equals(name)) return hookReloadEvent(buf);
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
                        // inject BEFORE the constructor's return (atEnd would be unreachable, after return)
                        @Override public void accept(CodeBuilder cob, CodeElement e) {
                            if (e instanceof java.lang.classfile.instruction.ReturnInstruction) {
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
                            cob.accept(e);
                        }
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
     * Root fix for the no-mod-bus init gap. LB registers its client reload listeners via NeoForge's
     * AddClientReloadListenersEvent (mod bus). With no ModFile that event never reaches LB, so its
     * reload listeners are never added and initializeClient() never runs at the correct time (during
     * the initial resource reload, before the first render tick). We replicate the mod-bus dispatch:
     * hook AddClientReloadListenersEvent.<init> and call NeoForgePlatform.onAddReloadListeners(this),
     * which adds LB's LazyReloadListener wrappers to the event exactly as the @Mod handler would.
     * initializeClient then runs at reload time (module registration + theme), matching the mod path
     * — fixing the KillAura init-order crash AND the render-timing crash (init racing tick 0).
     */
    static byte[] hookReloadEvent(byte[] buf) {
        try {
            ClassFile cf = ClassFile.of();
            ClassModel cm = cf.parse(buf);
            MethodTypeDesc onAdd = MethodTypeDesc.of(CD_void, CD_AddClientReloadEvent);
            ClassTransform ct = (cb, cle) -> {
                if (cle instanceof MethodModel mm && mm.methodName().equalsString("<init>")) {
                    cb.transformMethod(mm, MethodTransform.transformingCode(new CodeTransform() {
                        // inject BEFORE the constructor's return (atEnd would be unreachable, after return)
                        @Override public void accept(CodeBuilder cob, CodeElement e) {
                            if (e instanceof java.lang.classfile.instruction.ReturnInstruction) {
                                cob.aload(0) // the event
                                   .invokestatic(CD_NeoForgePlatform, "onAddReloadListeners", onAdd, false);
                                cob.getstatic(CD_System, "out", CD_PrintStream)
                                   .ldc("[NFAGENT] registered LB reload listeners via AddClientReloadListenersEvent (mod-bus replicated)")
                                   .invokevirtual(CD_PrintStream, "println", MTD_println);
                            }
                            cob.accept(e);
                        }
                    }));
                } else cb.accept(cle);
            };
            byte[] out = cf.transformClass(cm, ct);
            System.out.println("[NFAGENT] hooked AddClientReloadListenersEvent.<init> (" + buf.length + " -> " + out.length + ")");
            return out;
        } catch (Throwable t) { System.out.println("[NFAGENT] reload-event hook FAILED: " + t); return null; }
    }

    /** ((FMLMixinService)MixinService.getService()).addMixinConfigContent(cfg, ClassLoader.getSystemResourceAsStream(cfg).readAllBytes()); Mixins.addConfiguration(cfg);
     *  Root-level config JSONs are read from the system classloader (premain appended a resources-only
     *  jar via appendToSystemClassLoaderSearch) — the TransformingClassLoader no longer sees LB. */
    static void emitRegister(CodeBuilder cob, String cfg) {
        cob.invokestatic(CD_MixinService, "getService", MTD_getService, false)
           .checkcast(CD_FMLMixinService)
           .ldc(cfg)
           .ldc(cfg)
           .invokestatic(CD_ClassLoader, "getSystemResourceAsStream", MTD_getResourceAsStream, false)
           .invokevirtual(CD_InputStream, "readAllBytes", MTD_readAllBytes)
           .invokevirtual(CD_FMLMixinService, "addMixinConfigContent", MTD_addContent);
        cob.ldc(cfg)
           .invokestatic(CD_Mixins, "addConfiguration", MTD_addConfiguration, false);
    }
}
