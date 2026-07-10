package nfagent;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Child-first loader for LiquidBounce's own packages, installed as the fallback of NeoForge's
 * TransformingClassLoader (its parent). NeoForge analogue of Fabric addToClassPath(Knot):
 * LB classes are DEFINED by a loader that still sees net.minecraft.* (via the parent TCL).
 *
 * The TCL has exactly one fallback slot and FML already put a loader there (it resolves mixin
 * plugin API classes like IMixinConfigPlugin). We must NOT lose it: this loader chains to that
 * ORIGINAL fallback. Flow for a name the TCL bounces to us (its fallback):
 *   - LB-owned    -> define locally from LB URLs;
 *   - otherwise   -> ask the parent TCL (resolves MC/NeoForge/mixin via its modules/parents);
 *                    if that bounces back to us (loop), hand off to the original fallback.
 */
public final class LbLoader extends URLClassLoader {

    // LB-exclusive packages (not provided by the NeoForge/MC platform) — defined child-first so
    // LB's whole dependency graph shares ONE kotlin runtime (else okhttp-coroutines on the parent
    // loader and LB's kotlin on this loader disagree on kotlin.coroutines.Continuation -> LinkageError).
    private static final String[] OWNED = {
        "net/ccbluex/", "kotlin/", "kotlinx/", "okhttp3/", "okio/", "org/ahocorasick/",
    };

    private final ClassLoader original;         // FML's pre-existing TCL fallback (may be null)
    private final ThreadLocal<java.util.Set<String>> loadingNames = ThreadLocal.withInitial(java.util.HashSet::new);
    private final ThreadLocal<java.util.Set<String>> resolvingNames = ThreadLocal.withInitial(java.util.HashSet::new);
    private final ThreadLocal<java.util.Set<String>> resolvingMulti = ThreadLocal.withInitial(java.util.HashSet::new);

    public LbLoader(URL[] urls, ClassLoader parentTcl, ClassLoader original) {
        super("lb-agent-loader", urls, parentTcl);
        this.original = original;
    }

    private static boolean owned(String internal) {
        for (String p : OWNED) if (internal.startsWith(p)) return true;
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (owned(name.replace('.', '/'))) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name); // define locally from LB URLs
                if (resolve) resolveClass(c);
                return c;
            }
        }
        java.util.Set<String> inProg = loadingNames.get();
        if (inProg.contains(name)) {
            // the TCL bounced this back to us => it couldn't resolve it => defer to FML's original fallback
            if (original != null) return original.loadClass(name);
            throw new ClassNotFoundException(name);
        }
        inProg.add(name);
        try {
            return super.loadClass(name, resolve); // parent-first -> TCL (modules/parentLoaders)
        } finally {
            inProg.remove(name);
        }
    }

    @Override
    public URL getResource(String name) {
        if (owned(name)) {
            return findResource(name);
        }
        java.util.Set<String> inProg = resolvingNames.get();
        if (inProg.contains(name)) {
            return original != null ? original.getResource(name) : null;
        }
        inProg.add(name);
        try {
            return super.getResource(name); // parent-first -> TCL
        } finally {
            inProg.remove(name);
        }
    }

    @Override
    public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
        if (owned(name)) {
            return findResources(name);
        }
        java.util.Set<String> inProg = resolvingMulti.get();
        if (inProg.contains(name)) {
            return original != null ? original.getResources(name) : java.util.Collections.emptyEnumeration();
        }
        inProg.add(name);
        try {
            return super.getResources(name); // parent-first -> TCL
        } finally {
            inProg.remove(name);
        }
    }
}
