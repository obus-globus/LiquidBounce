package vspike;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.CommandLineOptions;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Side;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.io.InputStream;

public class Agent {
    public static void premain(String args, Instrumentation inst) throws Exception {
        System.out.println("[VSPIKE] premain start");
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap");
        System.setProperty("mixin.service", "vspike.VSpikeService");

        // Load AccessWidener (optional; only present when running against MC)
        final AccessWidener aw = loadAw("liquidbounce.accesswidener");
        if (aw != null) System.out.println("[VSPIKE] AccessWidener loaded: " + aw.directives + " directives");

        MixinBootstrap.init();
        Mixins.addConfiguration("vspike.mixins.json");
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);

        MixinPlatformManager pm = MixinBootstrap.getPlatform();
        pm.prepare(CommandLineOptions.defaultArgs());
        pm.inject();
        System.out.println("[VSPIKE] CURRENT phase (before) = " + MixinEnvironment.getCurrentEnvironment().getPhase());
        try {
            java.lang.reflect.Method gp = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
            gp.setAccessible(true);
            gp.invoke(null, MixinEnvironment.Phase.INIT);
            gp.invoke(null, MixinEnvironment.Phase.DEFAULT);
            System.out.println("[VSPIKE] forced gotoPhase -> DEFAULT");
        } catch (Throwable t){ System.out.println("[VSPIKE] gotoPhase reflection failed: " + t); }
        System.out.println("[VSPIKE] CURRENT phase (after)  = " + MixinEnvironment.getCurrentEnvironment().getPhase());

        VSpikeService svc = (VSpikeService) MixinService.getService();
        System.out.println("[VSPIKE] active service = " + svc.getClass().getName());
        try { var cn = svc.getBytecodeProvider().getClassNode("vspike.mixins.DummyMixin"); System.out.println("[VSPIKE] bytecodeProvider found DummyMixin: " + (cn!=null) + " name=" + (cn!=null?cn.name:"null")); }
        catch (Throwable e){ System.out.println("[VSPIKE] bytecodeProvider CANNOT find DummyMixin: " + e); }
        final IMixinTransformer transformer = svc.createTransformer();
        System.out.println("[VSPIKE] transformer = " + transformer);
        for (Config c : org.spongepowered.asm.mixin.Mixins.getConfigs()) {
            IMixinConfig mc = c.getConfig();
            System.out.println("[VSPIKE] config: " + c.getName() + " env=" + c.getEnvironment().getPhase() + " visited=" + c.isVisited() + " targets=" + mc.getTargets());
        }
        final MixinEnvironment defEnv = MixinEnvironment.getDefaultEnvironment();

        inst.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader, String className, Class<?> cbr, ProtectionDomain pd, byte[] buf){
                if (className == null) return null;
                boolean dbg = className.contains("Target");
                if (dbg) System.out.println("[VSPIKE] before: unvisited=" + org.spongepowered.asm.mixin.Mixins.getUnvisitedCount() + " targets=" + firstTargets());
                try {
                    byte[] cur = buf; boolean changed = false;
                    if (aw != null){ byte[] w = aw.apply(className, cur); if (w != null){ cur = w; changed = true; } }
                    String dotted = className.replace('/', '.');
                    byte[] mixed = transformer.transformClassBytes(dotted, dotted, cur);
                    if (dbg) System.out.println("[VSPIKE] after:  unvisited=" + org.spongepowered.asm.mixin.Mixins.getUnvisitedCount() + " targets=" + firstTargets() + " changed=" + (mixed!=null && mixed!=cur));
                    if (mixed != null && mixed != cur){ return mixed; }
                    return changed ? cur : null;
                } catch (Throwable t){ System.err.println("[VSPIKE] transform error for " + className + " -> " + t); StackTraceElement[] st=t.getStackTrace(); for(int i=0;i<Math.min(4,st.length);i++) System.err.println("      at "+st[i]); return null; }
            }
        }, false);
        System.out.println("[VSPIKE] transformer registered; premain done");
    }

    private static String firstTargets(){ for (var c : org.spongepowered.asm.mixin.Mixins.getConfigs()) return c.getName()+"->"+c.getConfig().getTargets(); return "<none>"; }

    private static AccessWidener loadAw(String res){
        try (InputStream in = Agent.class.getClassLoader().getResourceAsStream(res)) {
            if (in == null) return null;
            return new AccessWidener(in);
        } catch (Exception e){ System.err.println("[VSPIKE] AW load failed"); e.printStackTrace(); return null; }
    }
}
