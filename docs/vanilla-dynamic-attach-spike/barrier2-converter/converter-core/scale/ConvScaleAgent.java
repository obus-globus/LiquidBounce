import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.CommandLineOptions;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Side;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;
import org.objectweb.asm.ClassReader;
import vspike.VSpikeService;
import org.objectweb.asm.tree.*;
import java.io.*; import java.lang.instrument.*; import java.lang.reflect.Method;
import java.nio.file.*; import java.util.*; import java.util.jar.*;

/** MILESTONE 1: run the REAL LB Mixin engine over LB's real 159 mixins and feed every schema-changing
 *  transformed target through RetransformConverter, tallying clean-converts vs edge cases — at scale. */
public class ConvScaleAgent {
    static final ClassLoader SYS = ClassLoader.getSystemClassLoader();
    public static void agentmain(String a, Instrumentation inst) throws Exception {
        inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(), Map.of("java.lang", Set.of(ConvScaleAgent.class.getModule())), Set.of(), Map.of());
        System.out.println("[SCALE] staging LB bundle + bootstrapping real Mixin engine");
        File selfJar = new File(ConvScaleAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path tmp = Files.createTempDirectory("lb-scale-"); tmp.toFile().deleteOnExit();
        try (JarFile jf = new JarFile(selfJar)) { for (var en = jf.entries(); en.hasMoreElements();) { JarEntry e = en.nextElement(); String n = e.getName();
            if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue; Path out = tmp.resolve(new File(n).getName());
            try (InputStream in = jf.getInputStream(e)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); } inst.appendToSystemClassLoaderSearch(new JarFile(out.toFile())); } }
        Thread.currentThread().setContextClassLoader(SYS);
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap"); System.setProperty("mixin.service", "vspike.VSpikeService");
        vspike.AccessWidener aw = new vspike.AccessWidener(SYS.getResourceAsStream("liquidbounce.accesswidener")); { var fld = Class.forName("vspike.VSpikeBytecodeProvider").getDeclaredField("AW"); fld.setAccessible(true); fld.set(null, aw); }
        MixinBootstrap.init();
        for (String c : new String[]{"liquidbounce.mixins.json","liquidbounce-fabric.mixins.json"}) Mixins.addConfiguration(c);
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);
        MixinPlatformManager pm = MixinBootstrap.getPlatform(); pm.prepare(CommandLineOptions.defaultArgs()); pm.inject();
        Method gp = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class); gp.setAccessible(true);
        gp.invoke(null, MixinEnvironment.Phase.INIT); gp.invoke(null, MixinEnvironment.Phase.DEFAULT);
        IMixinTransformer tr = ((VSpikeService) MixinService.getService()).createTransformer();
        try { com.llamalad7.mixinextras.MixinExtrasBootstrap.init(); } catch (Throwable t) {}
        MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();

        List<String> targets = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(SYS.getResourceAsStream("lb-mixin-targets.txt")))) { String l; while ((l = r.readLine()) != null) if (!l.isBlank()) targets.add(l.trim()); }
        int transformed = 0, converted = 0, notransform = 0; List<String> fails = new ArrayList<>();
        for (String dotted : targets) {
            String internal = dotted.replace('.', '/');
            try {
                byte[] O; try (InputStream in = SYS.getResourceAsStream(internal + ".class")) { if (in == null) { fails.add(internal + " : no class bytes"); continue; } O = in.readAllBytes(); }
                byte[] X = tr.transformClassBytes(dotted, dotted, O);
                if (X == null || Arrays.equals(X, O)) { notransform++; continue; }
                transformed++;
                RetransformConverter.Result res = new RetransformConverter(internal).run(O, X);
                // schema check: target' fields/methods/interfaces (by name+desc) == O's
                ClassNode o = read(O), t = read(res.target);
                if (!keysF(o).equals(keysF(t)) || !keysM(o).equals(keysM(t)) || !new HashSet<>(o.interfaces).equals(new HashSet<>(t.interfaces)))
                    { fails.add(internal + " : schema mismatch after convert"); continue; }
                read(res.target); read(res.sidecar); // parse-check
                converted++;
            } catch (Throwable e) { fails.add(internal + " : " + rootType(e) + ": " + rootMsg(e)); }
        }
        System.out.println("[SCALE] targets=" + targets.size() + " transformed=" + transformed + " CONVERTED-CLEAN=" + converted + " no-transform=" + notransform + " FAILED=" + fails.size());
        for (String f : fails) System.out.println("[SCALE][FAIL] " + f);
    }
    static ClassNode read(byte[] b){ ClassReader r=new ClassReader(b); ClassNode n=new ClassNode(); r.accept(n, ClassReader.SKIP_FRAMES); return n; }
    static Set<String> keysF(ClassNode n){ Set<String> s=new HashSet<>(); for(FieldNode f:n.fields) s.add(f.name+" "+f.desc); return s; }
    static Set<String> keysM(ClassNode n){ Set<String> s=new HashSet<>(); for(MethodNode m:n.methods) s.add(m.name+" "+m.desc); return s; }
    static String rootType(Throwable t){ while(t.getCause()!=null) t=t.getCause(); return t.getClass().getSimpleName(); }
    static String rootMsg(Throwable t){ while(t.getCause()!=null) t=t.getCause(); return String.valueOf(t.getMessage()); }
}
