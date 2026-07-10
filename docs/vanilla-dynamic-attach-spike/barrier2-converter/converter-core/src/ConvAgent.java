import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.CommandLineOptions;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Side;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;
import org.objectweb.asm.ClassReader;
import java.lang.instrument.*;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;
import vspike.VSpikeService;
import vspike.VSpikeBytecodeProvider;
import vspike.AccessWidener;

/** Attach to a BARE fully-loaded MC. Run the real Mixin engine on already-loaded Minecraft, apply the
 *  AccessWidener, auto-convert the schema-changing result, define sidecar+state+synthetics, retransform. */
public class ConvAgent {
    static IMixinTransformer transformer; static MixinEnvironment env;
    static Method defineClass5; static final ClassLoader SYS = ClassLoader.getSystemClassLoader();
    static final Set<String> definedSynth = new HashSet<>();

    public static void agentmain(String a, Instrumentation inst) throws Exception {
        System.out.println("[CONVAUTO] agentmain; retransformSupported=" + inst.isRetransformClassesSupported());
        inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
            Map.of("java.lang", Set.of(ConvAgent.class.getModule())), Set.of(), Map.of());
        defineClass5 = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
        defineClass5.setAccessible(true);
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap");
        System.setProperty("mixin.service", "vspike.VSpikeService");
        Thread.currentThread().setContextClassLoader(SYS);

        AccessWidener aw = new AccessWidener(ConvAgent.class.getResourceAsStream("/conv.accesswidener"));
        VSpikeBytecodeProvider.AW = aw;
        System.out.println("[CONVAUTO] AccessWidener: " + aw.directives + " directives");

        MixinBootstrap.init();
        Mixins.addConfiguration("conv.mixins.json");
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);
        MixinPlatformManager pm = MixinBootstrap.getPlatform(); pm.prepare(CommandLineOptions.defaultArgs()); pm.inject();
        Method gp = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class); gp.setAccessible(true);
        gp.invoke(null, MixinEnvironment.Phase.INIT); gp.invoke(null, MixinEnvironment.Phase.DEFAULT);
        transformer = ((VSpikeService) MixinService.getService()).createTransformer();
        try { com.llamalad7.mixinextras.MixinExtrasBootstrap.init(); } catch (Throwable t) {}
        env = MixinEnvironment.getCurrentEnvironment();

        String dotted = "net.minecraft.client.Minecraft", internal = "net/minecraft/client/Minecraft";
        Class<?> target = null; for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(dotted)) { target = c; break; }
        if (target == null) { System.out.println("[CONVAUTO] target not loaded"); return; }
        ProtectionDomain pd = target.getProtectionDomain();

        byte[] O; try (var in = SYS.getResourceAsStream(internal + ".class")) { O = in.readAllBytes(); }
        byte[] Ow = aw.apply(internal, O); if (Ow != null) { O = Ow; System.out.println("[CONVAUTO] applied AccessWidener to target (window field widened)"); }
        byte[] X = transformer.transformClassBytes(dotted, dotted, O);
        System.out.println("[CONVAUTO] Mixin engine: O=" + O.length + "b -> X=" + X.length + "b");

        RetransformConverter conv = new RetransformConverter(internal);
        RetransformConverter.Result r = conv.run(O, X);
        System.out.println("[CONVAUTO] converter: droppedIfaces=" + r.droppedInterfaces.size()
            + " relocFields=" + r.relocatedFields.size() + " relocMethods=" + r.relocatedMethods.size());

        // define the Mixin synthetics referenced by target' / sidecar (Operation bridges etc.)
        defineSynthetics(r.target, pd); defineSynthetics(r.sidecar, pd);
        defineClass5.invoke(SYS, conv.stateName().replace('/', '.'), conv.stateBytes(), 0, conv.stateBytes().length, pd);
        defineClass5.invoke(SYS, r.sidecarName.replace('/', '.'), r.sidecar, 0, r.sidecar.length, pd);
        System.out.println("[CONVAUTO] defined sidecar + state + " + definedSynth.size() + " synthetic(s) into target loader");

        try { java.nio.file.Files.write(java.nio.file.Path.of("/tmp/dump-target.class"), r.target);
              java.nio.file.Files.write(java.nio.file.Path.of("/tmp/dump-sidecar.class"), r.sidecar); System.out.println("[CONVAUTO] DUMP written"); } catch(Throwable t){}
        final byte[] tp = r.target;
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) { return internal.equals(n) ? tp : null; }
        }, true);
        try { inst.retransformClasses(target); System.out.println("[CONVAUTO] retransformClasses(ALREADY-LOADED Minecraft) with AUTO-CONVERTED bytes: SUCCESS"); }
        catch (Throwable e) { System.out.println("[CONVAUTO] retransform FAILED -> " + e); e.printStackTrace(); }
        // --- interface-adder caller rewrite (build-time LB-caller-rewrite over bundled LB classes) ---
        Map<String,String[]> ifaceMap = new HashMap<>();
        for (String di : r.droppedInterfaces) ifaceMap.put(di, new String[]{internal, r.sidecarName});
        if (!ifaceMap.isEmpty()) {
            try {
                Class<?> callerCls = Class.forName("vspike.TestCaller", false, SYS);
                byte[] cb; try (var in = SYS.getResourceAsStream("vspike/TestCaller.class")) { cb = in.readAllBytes(); }
                final byte[] cr = RetransformConverter.rewriteCaller(cb, ifaceMap);
                inst.addTransformer(new ClassFileTransformer() { public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) { return "vspike/TestCaller".equals(n) ? cr : null; } }, true);
                inst.retransformClasses(callerCls);
                Object mc = Class.forName("net.minecraft.client.Minecraft", false, SYS).getMethod("getInstance").invoke(null);
                int x = (int) callerCls.getMethod("call", Object.class).invoke(null, mc);
                System.out.println("[CONVAUTO][LIVE] interface-adder: dropped " + ifaceMap.keySet() + " on target + rewrote LB caller; TestCaller.call(minecraft)=" + x + " (no ClassCastException, hook via sidecar)");
            } catch (Throwable e) { System.out.println("[CONVAUTO] caller-rewrite FAILED -> " + e); e.printStackTrace(); }
        }
    }

    static void defineSynthetics(byte[] classBytes, ProtectionDomain pd) {
        for (String internal : scanSyntheticRefs(classBytes)) {
            String d = internal.replace('/', '.');
            if (!definedSynth.add(d)) continue;
            try {
                try { Class.forName(d, false, SYS); continue; } catch (ClassNotFoundException notYet) {}
                byte[] sb = transformer.generateClass(env, d);
                if (sb == null) { System.out.println("[CONVAUTO] generateClass null " + d); continue; }
                defineSynthetics(sb, pd);
                defineClass5.invoke(SYS, d, sb, 0, sb.length, d.startsWith("org.spongepowered.asm.synthetic") ? null : pd);
                System.out.println("[CONVAUTO]   defined synthetic " + d);
            } catch (Throwable t) { System.out.println("[CONVAUTO] synthetic define failed " + d + " -> " + t); }
        }
    }
    static List<String> scanSyntheticRefs(byte[] b) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            ClassReader cr = new ClassReader(b); char[] buf = new char[cr.getMaxStringLength()];
            for (int i = 1; i < cr.getItemCount(); i++) { int off = cr.getItem(i); if (off == 0 || off - 1 < 0) continue;
                if ((b[off - 1] & 0xff) != 7) continue;
                try { String n = cr.readUTF8(off, buf); if (n != null && (n.startsWith("org/spongepowered/asm/synthetic/") || n.contains("$Anonymous$"))) out.add(n); } catch (Throwable ig) {} }
        } catch (Throwable ig) {}
        return new ArrayList<>(out);
    }
}
