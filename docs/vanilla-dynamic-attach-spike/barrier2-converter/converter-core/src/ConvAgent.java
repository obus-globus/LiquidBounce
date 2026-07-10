import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.CommandLineOptions;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Side;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;
import java.lang.instrument.*;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import vspike.VSpikeService;

/** Attach to a BARE, fully-loaded MC. Run the real Mixin engine on the ALREADY-LOADED Minecraft, auto-convert
 *  the schema-changing result with RetransformConverter, define the sidecar+state, and retransformClasses. */
public class ConvAgent {
    static IMixinTransformer transformer; static MixinEnvironment env;
    static Method defineClass5; static final ClassLoader SYS = ClassLoader.getSystemClassLoader();
    public static void agentmain(String a, Instrumentation inst) throws Exception {
        System.out.println("[CONVAUTO] agentmain; retransformSupported=" + inst.isRetransformClassesSupported());
        inst.redefineModule(Object.class.getModule(), java.util.Set.of(), java.util.Map.of(),
            java.util.Map.of("java.lang", java.util.Set.of(ConvAgent.class.getModule())), java.util.Set.of(), java.util.Map.of());
        defineClass5 = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
        defineClass5.setAccessible(true);
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap");
        System.setProperty("mixin.service", "vspike.VSpikeService");
        Thread.currentThread().setContextClassLoader(SYS);
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
        System.out.println("[CONVAUTO] target loaded=" + (target != null));
        if (target == null) return;
        byte[] O; try (var in = SYS.getResourceAsStream(internal + ".class")) { O = in.readAllBytes(); }
        byte[] X = transformer.transformClassBytes(dotted, dotted, O);
        System.out.println("[CONVAUTO] Mixin engine: O=" + O.length + "b -> X=" + X.length + "b (added members present)");
        RetransformConverter conv = new RetransformConverter(internal);
        RetransformConverter.Result r = conv.run(O, X);
        System.out.println("[CONVAUTO] converter: droppedIfaces=" + r.droppedInterfaces + " relocFields=" + r.relocatedFields + " relocMethods=" + r.relocatedMethods);
        // define sidecar + state into the system loader (referenced by target')
        defineClass5.invoke(SYS, conv.stateName().replace('/', '.'), conv.stateBytes(), 0, conv.stateBytes().length, target.getProtectionDomain());
        defineClass5.invoke(SYS, r.sidecarName.replace('/', '.'), r.sidecar, 0, r.sidecar.length, target.getProtectionDomain());
        System.out.println("[CONVAUTO] defined sidecar + state into target loader");
        final byte[] tp = r.target;
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) { return internal.equals(n) ? tp : null; }
        }, true);
        try { inst.retransformClasses(target); System.out.println("[CONVAUTO] retransformClasses(ALREADY-LOADED Minecraft) with AUTO-CONVERTED bytes: SUCCESS"); }
        catch (Throwable e) { System.out.println("[CONVAUTO] retransform FAILED -> " + e); }
    }
}
