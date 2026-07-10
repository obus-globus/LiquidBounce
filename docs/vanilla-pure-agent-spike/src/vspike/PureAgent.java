package vspike;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.CommandLineOptions;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Side;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PURE -javaagent (no custom launcher main class): bootstrap the standalone Mixin service, apply mixins to
 * system-classloader MC via a ClassFileTransformer, and — the point of the spike — DEFINE Mixin's runtime
 * synthetic classes directly into the system classloader so system-loaded MC can resolve them without a
 * transforming classloader owning the namespace.
 */
public class PureAgent {
    static IMixinTransformer transformer;
    static MixinEnvironment env;
    static Method defineClass;                 // reflective ClassLoader.defineClass(String,byte[],int,int)
    static final ClassLoader SYS = ClassLoader.getSystemClassLoader();
    static final java.util.Set<String> handled = ConcurrentHashMap.newKeySet();

    public static void premain(String args, Instrumentation inst) throws Exception {
        System.out.println("[PURE] pure -javaagent premain (no launcher main class)");
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap");
        System.setProperty("mixin.service", "vspike.VSpikeService");

        MixinBootstrap.init();
        Mixins.addConfiguration("pure.mixins.json");
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);
        MixinPlatformManager pm = MixinBootstrap.getPlatform();
        pm.prepare(CommandLineOptions.defaultArgs());
        pm.inject();
        Method gp = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        gp.setAccessible(true);
        gp.invoke(null, MixinEnvironment.Phase.INIT);
        gp.invoke(null, MixinEnvironment.Phase.DEFAULT);
        transformer = ((VSpikeService) MixinService.getService()).createTransformer();
        try { com.llamalad7.mixinextras.MixinExtrasBootstrap.init(); } catch (Throwable ignored) {}
        env = MixinEnvironment.getCurrentEnvironment();

        // reflective ClassLoader.defineClass — needs --add-opens java.base/java.lang=ALL-UNNAMED
        defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
        defineClass.setAccessible(true);
        System.out.println("[PURE] transformer ready; defineClass hook armed; phase=" + env.getPhase());

        inst.addTransformer(new CFT(), false);
        System.out.println("[PURE] transformer registered; premain done");
    }

    static final class CFT implements ClassFileTransformer {
        public byte[] transform(ClassLoader loader, String className, Class<?> cbr, ProtectionDomain pd, byte[] buf) {
            if (className == null) return null;
            try {
                String dotted = className.replace('/', '.');
                byte[] mixed = transformer.transformClassBytes(dotted, dotted, buf);
                if (mixed == null || mixed == buf) return null;
                // this class now references Mixin synthetics -> generate + define them into the system loader
                defineSyntheticsReferencedBy(mixed);
                return mixed;
            } catch (Throwable t) {
                System.err.println("[PURE] transform error " + className + " -> " + root(t));
                return null;
            }
        }
    }

    /** Find every org.spongepowered.asm.synthetic.* class the given bytecode references, and define each
     *  (dependencies first) into the system classloader from transformer.generateClass. */
    static void defineSyntheticsReferencedBy(byte[] classBytes) {
        for (String internal : scanSyntheticRefs(classBytes)) {
            String dotted = internal.replace('/', '.');
            if (!handled.add(dotted)) continue;
            try {
                try { Class.forName(dotted, false, SYS); continue; } catch (ClassNotFoundException notYet) { /* define it */ }
                byte[] sb = transformer.generateClass(env, dotted);
                if (sb == null) { System.out.println("[PURE] generateClass returned null for " + dotted); continue; }
                defineSyntheticsReferencedBy(sb);                        // define its own synthetic deps first
                defineClass.invoke(SYS, dotted, sb, 0, sb.length);
                System.out.println("[PURE] DEFINED synthetic into system loader: " + dotted + " (" + sb.length + " bytes)");
            } catch (Throwable t) {
                System.out.println("[PURE] synthetic define FAILED for " + dotted + " -> " + root(t));
            }
        }
    }

    /** Crude constant-pool scan for org/spongepowered/asm/synthetic/<internal-name> references. */
    static List<String> scanSyntheticRefs(byte[] b) {
        List<String> out = new ArrayList<>();
        final String needle = "org/spongepowered/asm/synthetic/";
        String s = new String(b, StandardCharsets.ISO_8859_1);
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            int j = i;
            while (j < s.length()) { char c = s.charAt(j); if (c == '/' || c == '$' || c == '_' || Character.isLetterOrDigit(c)) j++; else break; }
            String name = s.substring(i, j);
            if (name.length() > needle.length()) out.add(name);
            i = j;
        }
        return out;
    }

    static String root(Throwable t) { Throwable c = t; while (c.getCause() != null) c = c.getCause(); return c.toString(); }
}
