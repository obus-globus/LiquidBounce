package vspike;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

/** Transforming classloader: applies AccessWidener + Mixin to game/mod classes and
 *  generates Mixin's runtime synthetic classes (which a -javaagent cannot create). */
public class TransformingLoader extends URLClassLoader {
    private final IMixinTransformer transformer;
    private final MixinEnvironment env;
    private final AccessWidener aw;
    private static final String SYNTH = "org.spongepowered.asm.synthetic.";

    public TransformingLoader(URL[] urls, ClassLoader parent, IMixinTransformer t, MixinEnvironment e, AccessWidener a){
        super(urls, parent); this.transformer=t; this.env=e; this.aw=a;
    }

    /** Only game/mod classes (and Mixin's synthetic classes) are self-loaded and transformed;
     *  everything else (JDK, Kotlin, libraries, the Mixin framework) comes from the parent. */
    private static boolean selfLoad(String n){
        return n.startsWith(SYNTH)
            || n.startsWith("net.minecraft.")
            || n.startsWith("com.mojang.blaze3d.")
            || n.startsWith("com.mojang.math.")
            || n.startsWith("com.mojang.realmsclient.")
            || n.startsWith("net.ccbluex.");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) c = selfLoad(name) ? findClass(name) : getParent().loadClass(name);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            if (name.startsWith(SYNTH)) {
                byte[] b = transformer.generateClass(env, name);
                if (b == null) throw new ClassNotFoundException(name);
                definePackageFor(name);
                return defineClass(name, b, 0, b.length);
            }
            String path = name.replace('.', '/') + ".class";
            byte[] raw;
            try (InputStream in = getResourceAsStream(path)) {
                if (in == null) throw new ClassNotFoundException(name);
                raw = in.readAllBytes();
            }
            byte[] cur = raw;
            if (aw != null) { byte[] w = aw.apply(name.replace('.', '/'), cur); if (w != null) cur = w; }
            byte[] fin;
            try {
                byte[] mixed = transformer.transformClassBytes(name, name, cur);
                fin = (mixed != null) ? mixed : cur;
            } catch (Throwable mixErr) {
                // A single mixin failing to apply must not kill the class (or the game);
                // fall back to the access-widened but un-mixed bytes, like a -javaagent would.
                System.err.println("[VSPIKE] mixin apply failed on " + name + " -> loading un-mixed: " + rootMsg(mixErr));
                fin = cur;
            }
            definePackageFor(name);
            return defineClass(name, fin, 0, fin.length);
        } catch (ClassNotFoundException e) { throw e; }
        catch (Throwable t) { throw new ClassNotFoundException(name, t); }
    }

    private static String rootMsg(Throwable t){ Throwable c=t; while(c.getCause()!=null) c=c.getCause(); return c.toString(); }

    private void definePackageFor(String name){
        int i = name.lastIndexOf('.');
        if (i > 0) { String pkg = name.substring(0, i);
            if (getDefinedPackage(pkg) == null) { try { definePackage(pkg, null,null,null,null,null,null,null); } catch (IllegalArgumentException ignored) {} } }
    }
}
