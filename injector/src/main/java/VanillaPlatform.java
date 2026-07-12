import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.CommandLineOptions;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Side;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;
import java.io.*; import java.lang.instrument.Instrumentation; import java.lang.reflect.Method;
import java.nio.file.*; import java.security.ProtectionDomain; import java.util.jar.*;
import vspike.VSpikeService;
import lbrt.InjectionLogger;

/** Vanilla late-attach platform: MC + staged LB live on the SYSTEM classloader, and the agent stands up its OWN
 *  standalone Sponge Mixin service (VSpikeService) and applies LB's AccessWidener itself. Lifted verbatim from the
 *  proven FullInjectAgent code; behavior is identical. */
final class VanillaPlatform implements LoaderPlatform {
    private final ClassLoader sys = ClassLoader.getSystemClassLoader();
    private final Method defineClass5;                                        // reflective 5-arg ClassLoader.defineClass
    private volatile boolean opened;                                          // setAccessible deferred until java.lang is opened to the agent
    private IMixinTransformer tr; private MixinEnvironment env; private Object aw; private Method awApply;

    VanillaPlatform() throws Exception {
        defineClass5 = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
    }
    /** setAccessible only works once agentmain has opened java.lang to the agent module (redefineModule); the first
     *  defineClass call happens during stageBundle, after that open. Deferring keeps agentmain's original ordering. */
    private Method dc(){ if(!opened){ defineClass5.setAccessible(true); opened=true; } return defineClass5; }

    public ClassLoader targetLoader(){ return sys; }

    public byte[] originalBytes(String internalName){ try (InputStream in = sys.getResourceAsStream(internalName + ".class")) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; } }
    public byte[] bundleResource(String path){ try (InputStream in = sys.getResourceAsStream(path)) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; } }

    public Path stageBundle(Instrumentation inst, File agentJar) throws Exception {
        Path tmp = Files.createTempDirectory("lb-full-"); tmp.toFile().deleteOnExit();
        Path lbBundle = null;
        try (JarFile jf = new JarFile(agentJar)) { for (var en = jf.entries(); en.hasMoreElements();) { JarEntry e = en.nextElement(); String n = e.getName();
            if (n.equals("lbrt/AwReflect.class") || n.equals("lbrt/Platform.class") || ((n.startsWith("lbrt/DuckDispatch") || n.startsWith("lbrt/JoinGate")) && n.endsWith(".class"))) {
                byte[] b; try (InputStream in=jf.getInputStream(e)){ b=in.readAllBytes(); }
                if(!defineClass(n.substring(0, n.length()-6).replace('/','.'), b, null))
                    throw new IllegalStateException("Cannot stage runtime class "+n);
                continue;
            }
            if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue; Path o = tmp.resolve(new File(n).getName());
            try (InputStream in = jf.getInputStream(e)) { Files.copy(in, o, StandardCopyOption.REPLACE_EXISTING); }
            if(n.equals("agent-libs/liquidbounce.jar")) lbBundle=o;
            inst.appendToSystemClassLoaderSearch(new JarFile(o.toFile())); } }
        if(lbBundle==null) throw new IllegalStateException("Bundled liquidbounce.jar not found");
        Thread.currentThread().setContextClassLoader(sys);
        try { Class.forName("vspike.McefNative").getMethod("stageIfBundled", File.class, String.class).invoke(null, agentJar, InjectionLogger.PREFIX); } catch (Throwable t) {}
        return lbBundle;
    }

    public void initMixin() throws Exception {
        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap"); System.setProperty("mixin.service", "vspike.VSpikeService");
        aw = Class.forName("vspike.AccessWidener").getConstructor(InputStream.class).newInstance(sys.getResourceAsStream("liquidbounce.accesswidener"));
        { var f = Class.forName("vspike.VSpikeBytecodeProvider").getDeclaredField("AW"); f.setAccessible(true); f.set(null, aw); }
        MixinBootstrap.init();
        for (String c : new String[]{"liquidbounce.mixins.json","liquidbounce-fabric.mixins.json"}) Mixins.addConfiguration(c);
        MixinEnvironment.getDefaultEnvironment().setSide(Side.CLIENT);
        MixinPlatformManager pm = MixinBootstrap.getPlatform(); pm.prepare(CommandLineOptions.defaultArgs()); pm.inject();
        Method gp = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class); gp.setAccessible(true);
        gp.invoke(null, MixinEnvironment.Phase.INIT); gp.invoke(null, MixinEnvironment.Phase.DEFAULT);
        tr = ((VSpikeService) MixinService.getService()).createTransformer();
        try { com.llamalad7.mixinextras.MixinExtrasBootstrap.init(); } catch (Throwable t) {}
        env = MixinEnvironment.getCurrentEnvironment();
        awApply = aw.getClass().getMethod("apply", String.class, byte[].class);
    }

    public byte[] transform(String dotted, byte[] originalO){ byte[] X = tr.transformClassBytes(dotted, dotted, originalO); return (X==null||java.util.Arrays.equals(X,originalO))?null:X; }
    public byte[] generateClass(String dotted){ return tr.generateClass(env, dotted); }
    public Object accessWidener(){ return aw; }
    public boolean cftAppliesAw(){ return true; }
    public byte[] applyAw(String n, byte[] b){ try { return (byte[]) awApply.invoke(aw, n, b); } catch(Throwable t){ return null; } }

    public boolean defineClass(String dotted, byte[] b, ProtectionDomain pd){ try { dc().invoke(sys, dotted, b, 0, b.length, pd); return true; }
        catch(Throwable t){ Throwable c=t.getCause()!=null?t.getCause():t;
            String m=String.valueOf(c.getMessage());
            if (m.contains("duplicate")) return true;                         // already defined -> fine
            InjectionLogger.error("DEFINE-FAIL "+dotted+" -> "+c.getClass().getSimpleName()+": "+c.getMessage());
            return false; } }
}
