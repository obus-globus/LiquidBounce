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
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Launch vanilla MC with LiquidBounce injected via a transforming classloader (no -javaagent). */
public class VanillaLauncher {
    public static void main(String[] gameArgs) throws Exception {
        System.out.println("[VSPIKE] VanillaLauncher: bootstrapping Mixin");
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap");
        System.setProperty("mixin.service", "vspike.VSpikeService");

        final AccessWidener aw = loadAw(System.getProperty("vspike.accessWidener", "liquidbounce.accesswidener"));
        if (aw != null) System.out.println("[VSPIKE] AccessWidener: " + aw.directives + " directives");
        // Mixin's ClassInfo metadata must see the same AW-widened classes the transformer produces,
        // or injectors targeting a widened private method fail with LVTGeneratorError.
        VSpikeBytecodeProvider.AW = aw;

        MixinBootstrap.init();
        for (String c : System.getProperty("vspike.configs", "").split(",")) { c = c.trim(); if (!c.isEmpty()) { Mixins.addConfiguration(c); System.out.println("[VSPIKE] +config " + c); } }
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);
        MixinPlatformManager pm = MixinBootstrap.getPlatform();
        pm.prepare(CommandLineOptions.defaultArgs());
        pm.inject();
        Method gp = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        gp.setAccessible(true);
        gp.invoke(null, MixinEnvironment.Phase.INIT);
        gp.invoke(null, MixinEnvironment.Phase.DEFAULT);
        VSpikeService svc = (VSpikeService) MixinService.getService();
        IMixinTransformer transformer = svc.createTransformer();
        try { com.llamalad7.mixinextras.MixinExtrasBootstrap.init(); System.out.println("[VSPIKE] MixinExtras bootstrapped"); }
        catch (Throwable t){ System.out.println("[VSPIKE] MixinExtras FAILED: " + t); }
        MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
        System.out.println("[VSPIKE] phase=" + env.getPhase());

        // build URLs from the launch classpath
        List<URL> urls = new ArrayList<>();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator))
            if (!e.isEmpty()) urls.add(new File(e).toURI().toURL());
        TransformingLoader loader = new TransformingLoader(urls.toArray(new URL[0]),
            VanillaLauncher.class.getClassLoader(), transformer, env, aw);
        Thread.currentThread().setContextClassLoader(loader);

        System.out.println("[VSPIKE] launching Minecraft via transforming loader");
        Class<?> main = loader.loadClass("net.minecraft.client.main.Main");
        Method m = main.getMethod("main", String[].class);
        m.invoke(null, (Object) gameArgs);
    }

    private static AccessWidener loadAw(String res){
        try (InputStream in = VanillaLauncher.class.getClassLoader().getResourceAsStream(res)) {
            return in == null ? null : new AccessWidener(in);
        } catch (Exception e){ e.printStackTrace(); return null; }
    }
}
