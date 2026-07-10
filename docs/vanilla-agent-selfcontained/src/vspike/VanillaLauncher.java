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
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Self-contained entrypoint: launch UNMODIFIED vanilla Minecraft with LiquidBounce injected via a
 * transforming classloader — no -javaagent, no Gradle, no hand-assembled classpath, no -Dlb.* paths.
 *
 * This jar bundles, at its root, the Mixin framework (sponge-mixin + ASM + MixinExtras + the VSpike
 * standalone Mixin service) and, under agent-libs/, LiquidBounce's compiled classes/resources plus its
 * full LB-owned dependency tree. The bare-vanilla side (the MC client jar + piston libraries) is the
 * only thing the host provides — it is the classpath this main class is launched on.
 *
 * Flow: extract agent-libs/*.jar to a temp dir -> build `libLoader` over them (parent = the app loader
 * that holds the Mixin framework) -> set it as the context classloader so Mixin's config/AW/bytecode
 * reads resolve LB -> bootstrap Mixin + register LB configs + apply the AccessWidener -> run MC through
 * a TransformingLoader whose parent is `libLoader`.
 */
public class VanillaLauncher {
    public static void main(String[] gameArgs) throws Exception {
        System.out.println("[VSPIKE] VanillaLauncher (self-contained): staging bundled LB payload");
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap");
        System.setProperty("mixin.service", "vspike.VSpikeService");

        final ClassLoader app = VanillaLauncher.class.getClassLoader();     // holds the Mixin framework
        final List<URL> lbUrls = new ArrayList<>();

        // 1. extract the bundled LB payload (classes + resources + dep tree) out of THIS jar to temp
        final File selfJar = new File(VanillaLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final Path tmp = Files.createTempDirectory("lb-vanilla-agent-");
        tmp.toFile().deleteOnExit();
        if (selfJar.isFile()) {
            try (JarFile jf = new JarFile(selfJar)) {
                for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                    JarEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
                    Path out = tmp.resolve(new File(n).getName());
                    try (InputStream in = jf.getInputStream(e)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
                    out.toFile().deleteOnExit();
                    lbUrls.add(out.toUri().toURL());
                }
            }
        }
        System.out.println("[VSPIKE] staged " + lbUrls.size() + " bundled LB jars to " + tmp);

        // 2. libLoader over the extracted payload; parent = app loader (Mixin framework). Becomes the
        //    context classloader so Mixin's service resolves LB configs/classes/resources from the bundle.
        final URLClassLoader libLoader = new URLClassLoader("lb-lib", lbUrls.toArray(new URL[0]), app);
        Thread.currentThread().setContextClassLoader(libLoader);

        // 3. AccessWidener — read jar-relative from the bundled LB resources (namespace official = Mojmap 26.2)
        final AccessWidener aw = loadAw(libLoader, System.getProperty("vspike.accessWidener", "liquidbounce.accesswidener"));
        if (aw != null) System.out.println("[VSPIKE] AccessWidener: " + aw.directives + " directives");
        // Mixin's ClassInfo metadata must see the same AW-widened classes the transformer produces.
        VSpikeBytecodeProvider.AW = aw;

        // 4. bootstrap Mixin + register LB's configs (default to LB's real configs; no dev -D needed)
        MixinBootstrap.init();
        String configs = System.getProperty("vspike.configs", "liquidbounce.mixins.json,liquidbounce-fabric.mixins.json");
        for (String c : configs.split(",")) { c = c.trim(); if (!c.isEmpty()) { Mixins.addConfiguration(c); System.out.println("[VSPIKE] +config " + c); } }
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);
        MixinPlatformManager pm = MixinBootstrap.getPlatform();
        pm.prepare(CommandLineOptions.defaultArgs());
        pm.inject();
        // inject() does not advance the current phase; force it to DEFAULT so DEFAULT-phase configs prepare
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

        // 5. TransformingLoader over MC (from java.class.path) + the LB payload; parent = libLoader so
        //    kotlin/DJL/etc. resolve, while net.minecraft.* / net.ccbluex.* are self-loaded + transformed.
        List<URL> urls = new ArrayList<>();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator))
            if (!e.isEmpty()) urls.add(new File(e).toURI().toURL());
        urls.addAll(lbUrls);
        TransformingLoader loader = new TransformingLoader(urls.toArray(new URL[0]), libLoader, transformer, env, aw);
        Thread.currentThread().setContextClassLoader(loader);

        System.out.println("[VSPIKE] launching Minecraft via transforming loader");
        Class<?> main = loader.loadClass("net.minecraft.client.main.Main");
        Method m = main.getMethod("main", String[].class);
        m.invoke(null, (Object) gameArgs);
    }

    private static AccessWidener loadAw(ClassLoader cl, String res){
        try (InputStream in = cl.getResourceAsStream(res)) {
            return in == null ? null : new AccessWidener(in);
        } catch (Exception e){ e.printStackTrace(); return null; }
    }
}
