import org.spongepowered.asm.mixin.Mixins;                                  // FML's copy (same App-loader identity as the agent)
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.MixinService;
import java.io.*; import java.lang.instrument.Instrumentation; import java.lang.reflect.*;
import java.net.URL; import java.nio.file.*; import java.security.*; import java.util.*; import java.util.jar.*;
import lbrt.InjectionLogger;

/** NeoForge (FML 11 / TransformingClassLoader) late-attach platform.
 *
 *  FML's Sponge Mixin (MixinService/Mixins/MixinTransformer) and
 *  FMLMixinService all live on the SYSTEM/App classloader — the SAME loader the dynamically-attached agent lands on —
 *  so the Mixin types share one identity with the agent (like Fabric's Option B, but here for free). We therefore use
 *  the typed Mixin API directly (VanillaPlatform-style) and reach FMLMixinService.getMixinTransformer()/processor
 *  reflectively only where the concrete FML type isn't on the compile classpath.
 *
 *  Module system (the crux): MC is in named module `minecraft` on the TCL; sidecars CANNOT enter it (a package is owned
 *  by one module per loader). So LB is staged onto an LbLoader (child of the TCL, unnamed module) and sidecars are
 *  RELOCATED (RetransformConverter.SIDECAR_PKG) into an LB-owned package defined on the LbLoader; a target' running on
 *  the TCL resolves its sidecar across the loader boundary via the TCL's fallbackClassLoader (= LbLoader). The relocated
 *  sidecar is cross-package from every MC target, so the converter's pkg(caller)!=pkg(owner) checks route ALL non-public
 *  MC access through AwReflect. The transformer input / diff baseline stay RAW (non-AW-widened) so oFieldAcc reflects
 *  the true (non-public) access of already-loaded targets. TCL.parentLoaders/fallback are in the unnamed module -> no
 *  module opens needed. */
final class NeoForgePlatform implements LoaderPlatform {
    static final String TCL_NAME = "net.neoforged.fml.classloading.transformation.TransformingClassLoader";
    static final String SIDECAR_PKG = "net/ccbluex/lbrt/sc/";
    private final Instrumentation inst;
    private ClassLoader tcl;                         // TransformingClassLoader (owns net.minecraft.* in module `minecraft`)
    private ClassLoader agentLoader;                 // system/App loader (owns the agent + lbrt.* single identity + FML's Mixin)
    private LbLoader lb;                             // child-first LB loader (parent = TCL), holds LB + relocated sidecars
    private Object transformer;                      // org.spongepowered.asm.mixin.transformer.MixinTransformer (Object handle)
    private Object processor; private Field fTransformedCount, fErrorState;
    private Method mTransformClassBytes, mGenerateClass; private Object env;
    private Object aw; private Method awApply;
    private File agentJar;
    private final Map<String,byte[]> baseline = new HashMap<>();
    private final Set<String> ownedPkgs = new HashSet<>();
    private boolean lbActivated;

    NeoForgePlatform(Instrumentation inst){ this.inst = inst; }

    // ---- §1a: find the TransformingClassLoader via a loaded net.minecraft.* class -----------------------------------
    public ClassLoader targetLoader(){ if (tcl != null) return tcl; try {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            ClassLoader l = c.getClassLoader();
            if (c.getName().startsWith("net.minecraft.") && l != null && l.getClass().getName().equals(TCL_NAME)) { tcl = l; break; }
        }
        if (tcl == null) throw new IllegalStateException("TransformingClassLoader not found among loaded net.minecraft.* classes");
        agentLoader = NeoForgePlatform.class.getClassLoader();
        return tcl;
    } catch (Throwable t){ throw rt("targetLoader (TCL acquisition)", t); } }

    // ---- §1a/§1d/§3.3: stage LB onto an LbLoader; install it as the TCL fallback + register parentLoaders -----------
    public Path stageBundle(Instrumentation inst, File agentJar) throws Exception {
        this.agentJar = agentJar;
        RetransformConverter.SIDECAR_PKG = SIDECAR_PKG;                          // relocate sidecars out of module `minecraft`
        Path tmp = Files.createTempDirectory("lb-nf-"); tmp.toFile().deleteOnExit();
        List<URL> urls = new ArrayList<>(); Path lbBundle = null; int pushed = 0, skipped = 0;
        try (JarFile jf = new JarFile(agentJar)) { for (var en = jf.entries(); en.hasMoreElements();) { JarEntry e = en.nextElement(); String n = e.getName();
            if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
            String base = new File(n).getName();
            boolean isLb = base.equals("liquidbounce.jar");
            if (!isLb && isLoaderProvided(base)) { skipped++; continue; }         // asm/mixin/mixinextras -> NeoForge-provided (parent-first)
            Path o = tmp.resolve(base);
            try (InputStream in = jf.getInputStream(e)) { Files.copy(in, o, StandardCopyOption.REPLACE_EXISTING); } o.toFile().deleteOnExit();
            if (isLb) { lbBundle = o; scanPackages(o, ownedPkgs); }
            urls.add(o.toUri().toURL()); pushed++;
        } }
        if (lbBundle == null) throw new IllegalStateException("Bundled agent-libs/liquidbounce.jar not found");
        ownedPkgs.add(SIDECAR_PKG);                                              // relocated sidecars are defined + resolved here
        // Install LbLoader as the TCL's child-first fallback (chained to FML's original fallback) and register LB
        // packages in parentLoaders so FML's mixin byte reader locates LB mixin classes during transformation.
        ClassLoader original = readLoaderField(tcl, "fallbackClassLoader");
        lb = new LbLoader(urls.toArray(new URL[0]), tcl, original, agentLoader, ownedPkgs);
        tcl.getClass().getMethod("setFallbackClassLoader", ClassLoader.class).invoke(tcl, lb);
        registerParentLoaders(tcl, lb);
        InjectionLogger.info("staged LB onto LbLoader: "+pushed+" jar(s), "+skipped+" loader-provided skipped; ownedPkgs="+ownedPkgs.size()+"; TCL="+tcl);
        return lbBundle;
    }
    /** Hard identity-split hazards NeoForge provides itself — resolved parent-first via the TCL, never on the LbLoader.
     *  mcef is excluded too: the bundled Fabric-flavoured mcef jar's MCEF.<clinit> transitively references Sodium
     *  classes absent from sodium-neoforge, crashing the (stretch) browser stage; without it LB skips the browser. */
    private static boolean isLoaderProvided(String base){ String b=base.toLowerCase();
        return b.startsWith("asm-")||b.startsWith("asm.")||b.contains("sponge-mixin")||b.contains("mixinextras")||b.contains("mcef"); }

    static void scanPackages(Path jar, Set<String> owned) {
        try (JarFile jf = new JarFile(jar.toFile())) { for (var en = jf.entries(); en.hasMoreElements();) {
            String n = en.nextElement().getName();
            if (n.endsWith(".class")) { int i = n.lastIndexOf('/'); if (i > 0) owned.add(n.substring(0, i + 1)); }
        } } catch (Exception ignored) {}
    }
    @SuppressWarnings("unchecked")
    private void registerParentLoaders(ClassLoader t, ClassLoader lb) throws Exception {
        Field f = declaredUp(t.getClass(), "parentLoaders"); f.setAccessible(true);
        Map<String,ClassLoader> cur = (Map<String,ClassLoader>) f.get(t);
        Map<String,ClassLoader> map = cur; boolean replace = false;
        try { map.put("__lb_probe__", lb); map.remove("__lb_probe__"); }
        catch (UnsupportedOperationException uoe) { map = new HashMap<>(cur); replace = true; }
        int n = 0;
        for (String pkg : ownedPkgs) { String dotted = (pkg.endsWith("/") ? pkg.substring(0, pkg.length()-1) : pkg).replace('/', '.');
            if (map.putIfAbsent(dotted, lb) == null) n++; }
        if (replace) f.set(t, map);
        InjectionLogger.info("registered "+n+" LB packages into TCL.parentLoaders");
    }
    private static ClassLoader readLoaderField(Object o, String name){ try { Field f = declaredUp(o.getClass(), name); f.setAccessible(true); return (ClassLoader) f.get(o); } catch(Throwable t){ return null; } }
    private static Field declaredUp(Class<?> k, String name) throws NoSuchFieldException { for (; k != null; k = k.getSuperclass()) { try { return k.getDeclaredField(name); } catch(NoSuchFieldException e){} } throw new NoSuchFieldException(name); }

    // ---- §1c/§3.2: register LB configs into the LIVE FMLMixinService (required:false), acquire the live transformer -
    public void initMixin() throws Exception {
        byte[] awBytes = bundleResource("liquidbounce.accesswidener");
        if (awBytes == null) throw new IllegalStateException("liquidbounce.accesswidener not readable");
        aw = Class.forName("vspike.AccessWidener").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(awBytes));
        awApply = aw.getClass().getMethod("apply", String.class, byte[].class);

        Object svc = MixinService.getService();                                 // FMLMixinService (App loader)
        InjectionLogger.info("FML mixin service = "+svc.getClass().getName());
        Method addContent = findMethod(svc.getClass(), "addMixinConfigContent", String.class, byte[].class);
        // NeoForge runs FML-PATCHED MC classes, so the Fabric companions' injection points don't match and would fail
        // (skipping their whole target, including the shared render hooks). Register the NeoForge companion set instead.
        for (String cfg : new String[]{"liquidbounce.mixins.json","liquidbounce-neoforge.mixins.json"}) {
            byte[] cb = bundleResource(cfg);
            if (cb != null && addContent != null) addContent.invoke(svc, cfg, cb);
            Mixins.addConfiguration(cfg);
        }
        int downgraded = 0;
        for (Object cfg : Mixins.getConfigs()) {
            String name = (String) cfg.getClass().getMethod("getName").invoke(cfg);
            if (name == null || !name.contains("liquidbounce")) continue;
            Object mixinConfig; try { mixinConfig = cfg.getClass().getMethod("getConfig").invoke(cfg); } catch(Throwable t){ continue; }
            if (mixinConfig == null) continue;
            try { Field req = mixinConfig.getClass().getDeclaredField("required"); req.setAccessible(true); req.setBoolean(mixinConfig, false); downgraded++; } catch(Throwable t){}
        }
        if (downgraded == 0) throw new IllegalStateException("required:false workaround failed: no LB Config via Mixins.getConfigs()");
        InjectionLogger.info("registered LB mixin configs, downgraded "+downgraded+" to required:false");

        transformer = findMethod(svc.getClass(), "getMixinTransformer").invoke(svc);
        if (transformer == null) throw new IllegalStateException("FMLMixinService.getMixinTransformer() == null");
        Class<?> iTr = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer");
        mTransformClassBytes = iTr.getMethod("transformClassBytes", String.class, String.class, byte[].class); mTransformClassBytes.setAccessible(true);
        mGenerateClass = iTr.getMethod("generateClass", MixinEnvironment.class, String.class); mGenerateClass.setAccessible(true);
        env = MixinEnvironment.getCurrentEnvironment();
        Field fProc = declaredUp(transformer.getClass(), "processor"); fProc.setAccessible(true); processor = fProc.get(transformer);
        fTransformedCount = declaredUp(processor.getClass(), "transformedCount"); fTransformedCount.setAccessible(true);
        try { fErrorState = declaredUp(processor.getClass(), "errorState"); fErrorState.setAccessible(true); } catch(Throwable t){}
        InjectionLogger.info("acquired live FML transformer "+transformer.getClass().getName()+" (transformedCount="+fTransformedCount.getInt(processor)+")");
    }

    // ---- §1c: baselines (X_others) captured while LB's late-added config is UNVISITED -------------------------------
    public void prepareBaselines(java.util.List<String> internalTargets) throws Exception {
        if (lbActivated) throw new IllegalStateException("prepareBaselines after LB already activated");
        int n = 0;
        for (String internal : internalTargets) {
            byte[] pre = rawBytes(internal); if (pre == null) continue;
            byte[] xOthers; try { xOthers = (byte[]) mTransformClassBytes.invoke(transformer, internal.replace('/','.'), internal.replace('/','.'), pre); }
            catch (Throwable t){ continue; }
            baseline.put(internal, xOthers == null ? pre : xOthers); n++;
        }
        InjectionLogger.info("captured "+n+" non-LB mod baselines (X_others)");
    }

    // ---- §1c: X for a target = X_all after forcing LB's config to select; delta isolated against X_others ----------
    public byte[] transform(String dotted, byte[] originalO) throws Exception {
        String internal = dotted.replace('.','/');
        byte[] in = rawBytes(internal); if (in == null) in = originalO; if (in == null) return null;
        fTransformedCount.setInt(processor, 0);                                  // probe workaround: promote LB's pending config
        lbActivated = true;
        byte[] X;
        try { X = (byte[]) mTransformClassBytes.invoke(transformer, dotted, dotted, in); }
        catch (Throwable t) {
            // A Fabric-distribution mixin whose target method diverges on NeoForge-patched MC throws a critical
            // InjectionError ("Scanned 0 target(s)"). Treat the whole target as UNCONVERTED (skip) rather than a hard
            // failure that would abort the transactional gate, and clear the processor's error state for the next call.
            if (fErrorState != null) try { fErrorState.setBoolean(processor, false); } catch(Throwable ig){}
            Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
            InjectionLogger.warn("mixin skip "+internal+" -> "+c.getClass().getSimpleName()+": "+String.valueOf(c.getMessage()).split("\n")[0]);
            return null;
        }
        if (X == null) return null;
        byte[] base = originalO != null ? originalO : baseline.get(internal);
        if (base != null) X = FabricPlatform.alignSynthetics(base, X);
        return (base != null && Arrays.equals(X, base)) ? null : X;
    }
    public byte[] generateClass(String dotted){ try { return (byte[]) mGenerateClass.invoke(transformer, env, dotted); } catch(Throwable t){ return null; } }

    public Object accessWidener(){ return aw; }
    public boolean cftAppliesAw(){ return true; }                               // our CFT widens FUTURE MC classes (like vanilla)
    public byte[] applyAw(String internalName, byte[] bytes){ try { return (byte[]) awApply.invoke(aw, internalName, bytes); } catch(Throwable t){ return null; } }

    // ---- §1d: define relocated sidecar/state into the LB-owned package on the LbLoader; target' resolves via fallback
    public boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd){ try {
        lb.define(dotted, bytes); return true;
    } catch(Throwable t){ Throwable c = t.getCause()!=null?t.getCause():t; String m = String.valueOf(c.getMessage());
        if (c instanceof LinkageError && m.contains("duplicate")) return true;
        InjectionLogger.error("DEFINE-FAIL "+dotted+" -> "+c.getClass().getSimpleName()+": "+c.getMessage());
        return false; } }

    // ---- §1b: schema baseline O. mixin target -> X_others (loaded schema minus LB); else raw TCL bytes -------------
    public byte[] originalBytes(String internalName){ byte[] b = baseline.get(internalName); return b != null ? b : rawBytes(internalName); }
    private byte[] rawBytes(String internal){ try (InputStream in = tcl.getResourceAsStream(internal + ".class")) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; } }

    public byte[] bundleResource(String path){
        try (InputStream in = agentLoader.getResourceAsStream(path)) { if (in != null) return in.readAllBytes(); } catch(Throwable t){}
        try (InputStream in = lb != null ? lb.getResourceAsStream(path) : null) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; }
    }

    private static Method findMethod(Class<?> k, String name, Class<?>... params){ for (; k != null; k = k.getSuperclass()) { try { Method m = k.getDeclaredMethod(name, params); m.setAccessible(true); return m; } catch(NoSuchMethodException e){} } return null; }
    private static RuntimeException rt(String where, Throwable t){ Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
        return new IllegalStateException("NeoForgePlatform."+where+" failed: "+c.getClass().getSimpleName()+": "+c.getMessage(), c); }
}
