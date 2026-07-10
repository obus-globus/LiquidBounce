package vspike;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.CommandLineOptions;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Side;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * PURE -javaagent for vanilla: attaches to stock net.minecraft.client.main.Main on bare vanilla 26.2 with
 * NO custom launcher / main class. Everything (LB classes + resources + full dep tree + kotlin) is appended
 * to the SYSTEM classloader, so LB and MC share one loader (also dissolves the JOML/kotlin split the
 * launcher needed a workaround for). MC is transformed in place by a ClassFileTransformer, and Mixin's
 * runtime synthetic classes are DEFINED into the system loader from the transformer's synthetic registry
 * (a ClassFileTransformer can't fabricate them on demand) so system-loaded MC resolves them.
 *
 * Needs --add-opens java.base/java.lang=ALL-UNNAMED (reflective ClassLoader.defineClass).
 */
public class PureVanillaAgent {
    static IMixinTransformer transformer;
    static MixinEnvironment env;
    static AccessWidener aw;
    static Instrumentation INST;
    static Method defineClass5;                       // ClassLoader.defineClass(String,byte[],int,int,ProtectionDomain)
    static Map<String, ?> syntheticRegistry;          // MixinTransformer.syntheticClassRegistry.classes (name -> info)
    static final ClassLoader SYS = ClassLoader.getSystemClassLoader();
    static final java.util.Set<String> defined = ConcurrentHashMap.newKeySet();
    static final Map<String, ProtectionDomain> pdCache = new ConcurrentHashMap<>();
    static final ProtectionDomain NULL_PD = new ProtectionDomain(null, null);

    /** Dynamic-attach entrypoint (watcher injector): arm the exact same pipeline as premain. Classes already
     *  loaded before the attach lands are not caught (the early-boot coverage gap — see the watcher docs). */
    public static void agentmain(String args, Instrumentation inst) throws Exception { premain(args, inst); }

    static final java.util.concurrent.atomic.AtomicBoolean ARMED = new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void premain(String args, Instrumentation inst) throws Exception {
        if (!ARMED.compareAndSet(false, true)) { System.out.println("[PUREVANILLA] already armed; ignoring re-entry"); return; }
        INST = inst;
        System.out.println("[PUREVANILLA] pure agent premain/agentmain (stock Main, no launcher main class)");
        // Open java.base/java.lang to us via Instrumentation so reflective ClassLoader.defineClass (synthetic
        // injection) + ProcessEnvironment (offline-MCEF env) work WITHOUT any launch --add-opens flag. This is
        // what makes the no-flag dynamic-attach model viable (attached MC has no JVM flags at all).
        try {
            inst.redefineModule(Object.class.getModule(), java.util.Set.of(), java.util.Map.of(), java.util.Map.of(
                "java.lang", java.util.Set.of(PureVanillaAgent.class.getModule())), java.util.Set.of(), java.util.Map.of());
            System.out.println("[PUREVANILLA] opened java.base/java.lang via Instrumentation (no --add-opens needed)");
        } catch (Throwable t) { System.out.println("[PUREVANILLA] redefineModule failed (falling back to --add-opens if present): " + t); }
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap");
        System.setProperty("mixin.service", "vspike.VSpikeService");

        // 1. stage the bundled LB payload (classes + resources + dep tree) and APPEND it to the system loader
        File selfJar = new File(PureVanillaAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path tmp = Files.createTempDirectory("lb-purevanilla-");
        tmp.toFile().deleteOnExit();
        int staged = 0;
        try (JarFile jf = new JarFile(selfJar)) {
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
                Path out = tmp.resolve(new File(n).getName());
                try (InputStream in = jf.getInputStream(e)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
                out.toFile().deleteOnExit();
                inst.appendToSystemClassLoaderSearch(new JarFile(out.toFile()));
                staged++;
            }
        }
        System.out.println("[PUREVANILLA] appended " + staged + " bundled LB jars to the system classloader");
        Thread.currentThread().setContextClassLoader(SYS);

        // 2. optional offline MCEF (-PbundleMcefNative)
        McefNative.stageIfBundled(selfJar, "[PUREVANILLA]");

        // 3. bootstrap Mixin + register LB's real configs
        MixinBootstrap.init();
        for (String c : System.getProperty("vspike.configs", "liquidbounce.mixins.json,liquidbounce-fabric.mixins.json").split(",")) {
            c = c.trim(); if (!c.isEmpty()) { Mixins.addConfiguration(c); System.out.println("[PUREVANILLA] +config " + c); }
        }
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);
        MixinPlatformManager pm = MixinBootstrap.getPlatform();
        pm.prepare(CommandLineOptions.defaultArgs());
        pm.inject();
        Method gp = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        gp.setAccessible(true);
        gp.invoke(null, MixinEnvironment.Phase.INIT);
        gp.invoke(null, MixinEnvironment.Phase.DEFAULT);
        transformer = ((VSpikeService) MixinService.getService()).createTransformer();
        try { com.llamalad7.mixinextras.MixinExtrasBootstrap.init(); System.out.println("[PUREVANILLA] MixinExtras bootstrapped"); }
        catch (Throwable t) { System.out.println("[PUREVANILLA] MixinExtras FAILED: " + t); }
        env = MixinEnvironment.getCurrentEnvironment();

        // 4. AccessWidener (jar-relative; now resolvable on the system loader) + metadata sync
        aw = loadAw(System.getProperty("vspike.accessWidener", "liquidbounce.accesswidener"));
        if (aw != null) System.out.println("[PUREVANILLA] AccessWidener: " + aw.directives + " directives");
        VSpikeBytecodeProvider.AW = aw;

        // 5. reflective defineClass + the synthetic registry (complete source of registered synthetics)
        defineClass5 = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
        defineClass5.setAccessible(true);                        // needs --add-opens java.base/java.lang=ALL-UNNAMED
        syntheticRegistry = findSyntheticRegistry(transformer);
        System.out.println("[PUREVANILLA] transformer ready; synthetic registry " + (syntheticRegistry != null ? "hooked" : "NOT FOUND") + "; phase=" + env.getPhase());

        inst.addTransformer(new CFT(), false);
        System.out.println("[PUREVANILLA] transformer registered; premain done");
    }

    static final class CFT implements ClassFileTransformer {
        public byte[] transform(ClassLoader loader, String className, Class<?> cbr, ProtectionDomain pd, byte[] buf) {
            if (className == null) return null;
            // Only ever touch game namespaces. Critically, NEVER JDK/system classes: when attached very early
            // (during the signed MC jar's verification) a transform call on e.g. sun.security.* re-enters class
            // loading and throws ClassCircularityError, killing MC. AW + all LB mixin targets are net.minecraft/
            // com.mojang; LB's own classes need no transform.
            if (!(className.startsWith("net/minecraft/") || className.startsWith("com/mojang/"))) return null;
            try {
                byte[] cur = buf; boolean changed = false;
                if (aw != null) { byte[] w = aw.apply(className, cur); if (w != null) { cur = w; changed = true; } }
                String dotted = className.replace('/', '.');
                if (transformer.couldTransformClass(env, dotted)) {
                    byte[] mixed = transformer.transformClassBytes(dotted, dotted, cur);
                    if (mixed != null && mixed != cur) { cur = mixed; changed = true; }
                }
                // Define — right here, before returning THIS class — every Mixin synthetic its transformed
                // bytecode references, using THIS class's ProtectionDomain. Timing matters: the synthetic goes
                // into the target's package (MixinExtras $Anonymous$) which is SIGNED (MC jar); defining with the
                // target's own signed PD keeps the per-package signer check happy, and doing it at the target's
                // transform (not eagerly at premain) guarantees the package's signer is the target's.
                if (changed) for (String ref : scanSyntheticRefs(cur)) defineSynthetic(ref.replace('/', '.'), pd);
                return changed ? cur : null;
            } catch (Throwable t) {
                System.err.println("[PUREVANILLA] transform error " + className + " -> " + root(t));
                return null;
            }
        }
    }

    /** Generate + define a Mixin synthetic into the system loader (its own synthetic deps first). Sponge
     *  synthetics (org.spongepowered.asm.synthetic.*) are unsigned -> null PD; MixinExtras $Anonymous$
     *  synthetics live in the referencing class's package -> use that class's ProtectionDomain (ownerPd). */
    static void defineSynthetic(String dotted, ProtectionDomain ownerPd) {
        if (!defined.add(dotted)) return;
        try {
            try { Class.forName(dotted, false, SYS); return; } catch (ClassNotFoundException notYet) { /* define */ }
            byte[] sb = transformer.generateClass(env, dotted);
            if (sb == null) { System.out.println("[PUREVANILLA] generateClass null for " + dotted); return; }
            for (String ref : scanSyntheticRefs(sb)) defineSynthetic(ref.replace('/', '.'), ownerPd); // deps first
            ProtectionDomain pd = dotted.startsWith("org.spongepowered.asm.synthetic") ? null : ownerPd;
            defineClass5.invoke(SYS, dotted, sb, 0, sb.length, pd);
            System.out.println("[PUREVANILLA] defined synthetic: " + dotted + " (" + sb.length + "b, pd=" + (pd != null) + ")");
        } catch (Throwable t) {
            System.out.println("[PUREVANILLA] synthetic define FAILED " + dotted + " -> " + root(t));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, ?> findSyntheticRegistry(Object transformer) {
        try {
            Field rf = null;
            for (Class<?> c = transformer.getClass(); c != null && rf == null; c = c.getSuperclass()) {
                try { rf = c.getDeclaredField("syntheticClassRegistry"); } catch (NoSuchFieldException ignore) {}
            }
            if (rf == null) return null;
            rf.setAccessible(true);
            Object reg = rf.get(transformer);
            Field cf = reg.getClass().getDeclaredField("classes");
            cf.setAccessible(true);
            return (Map<String, ?>) cf.get(reg);
        } catch (Throwable t) { System.out.println("[PUREVANILLA] registry hook failed: " + t); return null; }
    }

    /** Every Mixin synthetic the given bytecode references: sponge synthetics (org/spongepowered/asm/synthetic/*)
     *  and MixinExtras "$Anonymous$" synthetics (in the referencing class's own package). Enumerated from the
     *  constant pool's CONSTANT_Class entries via ASM (robust vs a raw byte scan). */
    static List<String> scanSyntheticRefs(byte[] b) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(b);
            char[] buf = new char[cr.getMaxStringLength()];
            int n = cr.getItemCount();
            for (int i = 1; i < n; i++) {
                int off = cr.getItem(i);
                if (off == 0 || off - 1 < 0) continue;
                if ((b[off - 1] & 0xff) != 7) continue;         // CONSTANT_Class
                try {
                    String name = cr.readUTF8(off, buf);
                    if (name != null && (name.startsWith("org/spongepowered/asm/synthetic/") || name.contains("$Anonymous$")))
                        out.add(name);
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return new ArrayList<>(out);
    }

    static AccessWidener loadAw(String res) {
        try (InputStream in = SYS.getResourceAsStream(res)) { return in == null ? null : new AccessWidener(in); }
        catch (Exception e) { e.printStackTrace(); return null; }
    }

    static String root(Throwable t) { Throwable c = t; while (c.getCause() != null) c = c.getCause(); return c.toString(); }
}
