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
import java.security.ProtectionDomain;
import java.io.InputStream;

public class Agent {
    public static void premain(String args, Instrumentation inst) throws Exception {
        System.out.println("[VSPIKE] agent premain: bootstrapping Mixin over instrumentation");
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap");
        System.setProperty("mixin.service", "vspike.VSpikeService");

        final String awRes = System.getProperty("vspike.accessWidener", "liquidbounce.accesswidener");
        final AccessWidener aw = loadAw(awRes);
        if (aw != null) System.out.println("[VSPIKE] AccessWidener '" + awRes + "': " + aw.directives + " directives");

        MixinBootstrap.init();
        String configs = System.getProperty("vspike.configs", "vspike.mixins.json");
        for (String c : configs.split(",")) { c = c.trim(); if (!c.isEmpty()) { Mixins.addConfiguration(c); System.out.println("[VSPIKE] +config " + c); } }
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);

        MixinPlatformManager pm = MixinBootstrap.getPlatform();
        pm.prepare(CommandLineOptions.defaultArgs());
        pm.inject();
        // inject() does not advance the current phase; force it to DEFAULT so configs prepare
        var gp = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        gp.setAccessible(true);
        gp.invoke(null, MixinEnvironment.Phase.INIT);
        gp.invoke(null, MixinEnvironment.Phase.DEFAULT);

        VSpikeService svc = (VSpikeService) MixinService.getService();
        final IMixinTransformer transformer = svc.createTransformer();
        try { com.llamalad7.mixinextras.MixinExtrasBootstrap.init(); System.out.println("[VSPIKE] MixinExtras bootstrapped"); }
        catch (Throwable t){ System.out.println("[VSPIKE] MixinExtras bootstrap FAILED: " + t); }
        System.out.println("[VSPIKE] phase=" + MixinEnvironment.getCurrentEnvironment().getPhase() + " transformer=" + transformer);

        inst.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader, String className, Class<?> cbr, ProtectionDomain pd, byte[] buf){
                if (className == null) return null;
                try {
                    byte[] cur = buf; boolean changed = false;
                    if (aw != null){ byte[] w = aw.apply(className, cur); if (w != null){ cur = w; changed = true; } }
                    String dotted = className.replace('/', '.');
                    byte[] mixed = transformer.transformClassBytes(dotted, dotted, cur);
                    if (mixed != null && mixed != cur) return mixed;
                    return changed ? cur : null;
                } catch (Throwable t){
                    System.err.println("[VSPIKE] transform error for " + className + " -> " + t);
                    for (Throwable c=t; c!=null; c=c.getCause()){ System.err.println("   cause: " + c);
                        for (int i=0;i<Math.min(3,c.getStackTrace().length);i++) System.err.println("      at "+c.getStackTrace()[i]); }
                    return null;
                }
            }
        }, false);
        System.out.println("[VSPIKE] transformer registered; premain done");
    }

    private static AccessWidener loadAw(String res){
        try (InputStream in = Agent.class.getClassLoader().getResourceAsStream(res)) {
            if (in == null){ System.out.println("[VSPIKE] no AW resource '" + res + "'"); return null; }
            return new AccessWidener(in);
        } catch (Exception e){ e.printStackTrace(); return null; }
    }
}
