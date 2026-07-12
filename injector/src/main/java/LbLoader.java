import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/** Child-first loader for LiquidBounce's own packages + relocated sidecars, installed as the fallback of NeoForge's
 *  TransformingClassLoader (its parent). Adapted from docs/neoforge-agent-selfcontained nfagent.LbLoader, with two
 *  late-attach additions:
 *   - {@code define(name,bytes)} — the host defines relocated sidecars/state into an LB-owned package here (JPMS
 *     forbids defining them into module `minecraft`); a TCL-loaded target' resolves them via the TCL fallback -> this.
 *   - {@code shared} routing — lbrt.* runtime helpers (AwReflect/DuckDispatch/JoinGate/Platform) must be ONE identity
 *     shared by the agent (system loader), LB, and the sidecars; this loader delegates lbrt.* to the agent loader so
 *     Platform.LOADER / DuckDispatch's tables are the same objects the host populated. */
public final class LbLoader extends URLClassLoader {
    private final Set<String> ownedPkgs;          // e.g. "net/ccbluex/liquidbounce/features/", "net/ccbluex/lbrt/sc/"
    private final ClassLoader original;           // FML's pre-existing TCL fallback (may be null)
    private final ClassLoader shared;             // the agent (system) loader that owns the single lbrt.* identity
    private final ThreadLocal<Set<String>> loadingNames = ThreadLocal.withInitial(HashSet::new);
    private final ThreadLocal<Set<String>> resolvingNames = ThreadLocal.withInitial(HashSet::new);
    private final ThreadLocal<Set<String>> resolvingMulti = ThreadLocal.withInitial(HashSet::new);

    public LbLoader(URL[] urls, ClassLoader parentTcl, ClassLoader original, ClassLoader shared, Set<String> ownedPkgs) {
        super("lb-agent-loader", urls, parentTcl);
        this.original = original;
        this.shared = shared;
        this.ownedPkgs = ownedPkgs;
    }

    /** Define a host-generated class (relocated sidecar / state) into this loader's LB-owned package. */
    public synchronized Class<?> define(String dotted, byte[] b) {
        Class<?> existing = findLoadedClass(dotted);
        if (existing != null) return existing;
        return defineClass(dotted, b, 0, b.length);
    }

    private boolean owned(String internal) {
        int i = internal.lastIndexOf('/');
        if (i <= 0) return false;
        return ownedPkgs.contains(internal.substring(0, i + 1));
    }
    private static boolean isLbrt(String name) { return name.startsWith("lbrt."); }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // lbrt.* -> the agent loader's single copy (Platform.LOADER / DuckDispatch tables set by the host live there).
        if (isLbrt(name) && shared != null) {
            Class<?> c = shared.loadClass(name);
            if (resolve) resolveClass(c);
            return c;
        }
        // Host-defined classes (relocated sidecars/state, generated synthetics) resolve here regardless of package.
        Class<?> already = findLoadedClass(name);
        if (already != null) { if (resolve) resolveClass(already); return already; }
        if (owned(name.replace('.', '/'))) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name);   // define locally from LB URLs (or already define()d sidecar)
                if (resolve) resolveClass(c);
                return c;
            }
        }
        Set<String> inProg = loadingNames.get();
        if (inProg.contains(name)) {
            if (original != null) return original.loadClass(name);
            throw new ClassNotFoundException(name);
        }
        inProg.add(name);
        try {
            return super.loadClass(name, resolve);     // parent-first -> TCL (modules/parentLoaders)
        } finally {
            inProg.remove(name);
        }
    }

    @Override
    public URL getResource(String name) {
        if (owned(name)) return findResource(name);
        Set<String> inProg = resolvingNames.get();
        if (inProg.contains(name)) return original != null ? original.getResource(name) : null;
        inProg.add(name);
        try { return super.getResource(name); }
        finally { inProg.remove(name); }
    }

    @Override
    public Enumeration<URL> getResources(String name) throws java.io.IOException {
        if (owned(name)) return findResources(name);
        Set<String> inProg = resolvingMulti.get();
        if (inProg.contains(name)) return original != null ? original.getResources(name) : Collections.emptyEnumeration();
        inProg.add(name);
        try { return super.getResources(name); }
        finally { inProg.remove(name); }
    }
}
