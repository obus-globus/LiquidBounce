import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
import java.io.*; import java.lang.instrument.Instrumentation; import java.lang.reflect.*;
import java.net.URL; import java.nio.file.*; import java.security.*; import java.util.*; import java.util.jar.*;
import lbrt.InjectionLogger;

/** LabyMod 4 (legacy LaunchWrapper) late-attach platform.
 *
 *  LabyMod boots via net.minecraft.launchwrapper.Launch; MC 26.2 (Mojmap) is loaded by a
 *  net.minecraft.launchwrapper.LaunchClassLoader (a URLClassLoader implementing net.laby.launcher LabyClassLoader),
 *  with a LIVE Sponge-Mixin engine whose host service is net.labymod...LabyModMixinService. The Mixin framework has two
 *  copies in the JVM: the agent's (AppClassLoader) copy is INVALID; the VALID engine is the one reachable THROUGH the
 *  LaunchClassLoader. So — like FabricPlatform — every Mixin seam is driven by REFLECTION on Object handles resolved via
 *  the LaunchClassLoader, never cast to the agent's own Mixin types.
 *
 *  Shape (mirrors FabricPlatform, the other defer-into-live-engine port):
 *   - targetLoader  = the live LaunchClassLoader (found via Instrumentation: loader of net.minecraft.client.Minecraft;
 *     LabyMod's fork leaves the Launch.classLoader static null).
 *   - transformer   = the live IMixinTransformer behind the LaunchWrapper `org.spongepowered.asm.mixin.transformer.Proxy`
 *     (getActiveTransformer() is null under LabyMod; the transformer is pulled from the Proxy in getTransformers()).
 *   - baseline O    = LabyMod-transformed, LB-unvisited bytes (prepareBaselines runs the live transformer before LB's
 *     config is selected) — LabyMod's own mixins are already applied to the loaded classes, so raw bytes would mismatch.
 *   - AW            = LB applies its OWN AccessWidener (vspike.AccessWidener), since LabyMod's AccessWidenerController
 *     only widens LabyMod's needs; future MC classes are widened by the CFT (cftAppliesAw==true), already-loaded via
 *     INACC/AwReflect.
 *   - define/stage  = LaunchClassLoader is a plain URLClassLoader → addURL to stage LB, SecureClassLoader.defineClass to
 *     publish sidecars. lbrt.* is defined INTO the LaunchClassLoader (its parent chain excludes the agent's loader), so
 *     the agent-side Platform.LOADER / AwReflect tables and the sidecars share one identity there. */
final class LabyModPlatform implements LoaderPlatform {
    private final Instrumentation inst;
    private ClassLoader lcl;                 // live LaunchClassLoader (owns MC + staged LB)
    private Method mGetClassBytes;           // LaunchClassLoader.getClassBytes(String) -> raw
    private Method mAddURL;                   // URLClassLoader.addURL(URL) (protected)
    private Method mDefineClass5;             // ClassLoader.defineClass(String,byte[],int,int,ProtectionDomain) (java.lang)
    private volatile boolean dcOpened;        // setAccessible on defineClass deferred until agentmain opens java.lang
    private Object service;                    // LabyModMixinService (via LCL) — for transformExceptMixinProxy
    private Method mTransformExceptProxy;      // LabyModMixinService.transformExceptMixinProxy(String,byte[]) (private)
    private Object transformer;                // IMixinTransformer (LCL-visible; Object handle only)
    private Object processor; private Field fTransformedCount;
    private Method mTransformClassBytes, mGenerateClass; private Object env;
    private Field fConfigs;                     // MixinProcessor.configs (List<MixinConfig>)
    private final java.util.List<Object> lbConfigs = new java.util.ArrayList<>();  // LB's MixinConfigs (common + fabric companion)
    private java.util.List<Object> allConfigs;  // full config list (LabyMod's + LB) to restore after each LB-only pass
    private Object aw; private Method awApply;  // vspike.AccessWidener + apply(String,byte[])
    private File agentJar;
    private final Map<String,byte[]> baseline = new HashMap<>();
    private boolean lbActivated;

    LabyModPlatform(Instrumentation inst){ this.inst = inst; }

    // ---- target loader: the live LaunchClassLoader ------------------------------------------------------------------
    public ClassLoader targetLoader(){ try {
        for (Class<?> c : inst.getAllLoadedClasses())
            if (c.getName().equals("net.minecraft.client.Minecraft")) { lcl = c.getClassLoader(); break; }
        if (lcl == null) throw new IllegalStateException("LaunchClassLoader not found (net.minecraft.client.Minecraft unloaded)");
        mGetClassBytes = findUp(lcl.getClass(), "getClassBytes", String.class);
        if (mGetClassBytes == null) throw new NoSuchMethodException("getClassBytes(String) on "+lcl.getClass().getName());
        mGetClassBytes.setAccessible(true);
        mAddURL = findUp(lcl.getClass(), "addURL", URL.class); mAddURL.setAccessible(true);
        // Define via ClassLoader.defineClass(...,ProtectionDomain) — java.lang (opened by agentmain), NOT
        // SecureClassLoader.defineClass(...,CodeSource) which lives in java.security (not opened). setAccessible is
        // deferred to first use (dc()) because targetLoader() runs BEFORE agentmain opens java.lang.
        mDefineClass5 = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
        InjectionLogger.info("LabyMod target loader = "+lcl);
        return lcl;
    } catch (Throwable t){ throw rt("targetLoader", t); } }
    private Method dc(){ if(!dcOpened){ mDefineClass5.setAccessible(true); dcOpened=true; } return mDefineClass5; }

    // ---- stage LB onto the LaunchClassLoader (skip asm/mixin/mixinextras/kotlin already present) ---------------------
    public Path stageBundle(Instrumentation inst, File agentJar) throws Exception {
        this.agentJar = agentJar;
        // lbrt.* single identity. The agent + its populated lbrt.* helpers (Platform.LOADER, DuckDispatch tables, JoinGate
        // state) live on the AGENT loader. LabyMod's LaunchClassLoader can delegate a registered prefix to its
        // `appClassLoader` field — but that field is LabyMod's launcher loader, NOT the agent loader, so it can't see
        // lbrt.*. Install a thin shim as appClassLoader that routes lbrt.* to the agent loader and everything else to the
        // original launcher loader (so LabyMod's own delegations/fallbacks are unaffected), then register the lbrt.
        // prefix. LB + the sidecars now resolve lbrt.* to the SAME instances the agent populates. Defining a second copy
        // into LaunchClassLoader instead split that state and left AwReflect.SYS (= Platform.LOADER) null -> init NPE.
        final ClassLoader agentLoader = LabyModPlatform.class.getClassLoader();
        Field fApp = findFieldUp(lcl.getClass(), "appClassLoader"); fApp.setAccessible(true);
        final ClassLoader orig = (ClassLoader) fApp.get(lcl);
        ClassLoader shim = new ClassLoader(orig) {
            @Override protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (n.startsWith("lbrt.")) { Class<?> c = agentLoader.loadClass(n); if (resolve) resolveClass(c); return c; }
                return super.loadClass(n, resolve);
            }
            @Override public java.net.URL getResource(String n){ java.net.URL u = agentLoader.getResource(n); return u != null ? u : orig.getResource(n); }
        };
        fApp.set(lcl, shim);
        Method mApd = findUp(lcl.getClass(), "addParentDelegation", String.class);
        if (mApd == null) throw new IllegalStateException("LaunchClassLoader.addParentDelegation(String) not found; cannot route lbrt.* single-identity");
        mApd.invoke(lcl, "lbrt.");
        InjectionLogger.info("routed lbrt.* to the agent loader ("+agentLoader+") via LaunchClassLoader parent-delegation shim");
        Set<String> onCp = new HashSet<>();
        for (URL u : ((java.net.URLClassLoader) lcl).getURLs()) { String b=new File(u.getPath()).getName().toLowerCase(); if(!b.isEmpty()) onCp.add(b); }
        Path tmp = Files.createTempDirectory("lb-laby-"); tmp.toFile().deleteOnExit();
        Path lbBundle = null; int pushed=0, skipped=0;
        try (JarFile jf = new JarFile(agentJar)) { for (var en = jf.entries(); en.hasMoreElements();) { JarEntry e = en.nextElement(); String n = e.getName();
            if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
            String base = new File(n).getName(); boolean isLb = base.equals("liquidbounce.jar");
            if (!isLb && (onCp.contains(base.toLowerCase()) || isLoaderProvided(base))) { skipped++; continue; }
            Path o = tmp.resolve(base); try (InputStream in=jf.getInputStream(e)){ Files.copy(in,o,StandardCopyOption.REPLACE_EXISTING); } o.toFile().deleteOnExit();
            mAddURL.invoke(lcl, o.toUri().toURL());
            if (isLb) lbBundle = o;
            pushed++;
        } }
        if (lbBundle == null) throw new IllegalStateException("Bundled agent-libs/liquidbounce.jar not found");
        try { Class.forName("vspike.McefNative").getMethod("stageIfBundled", File.class, String.class).invoke(null, agentJar, InjectionLogger.PREFIX); } catch (Throwable t) {}
        InjectionLogger.info("staged onto LaunchClassLoader: pushed "+pushed+" jar(s), skipped "+skipped+" already-present");
        return lbBundle;
    }
    // Only the hard identity-split hazards (ASM/Mixin/MixinExtras — a second copy on LaunchClassLoader would fork type
    // identity with LabyMod's live engine). Everything else is decided by the onCp check (skip only if the exact jar is
    // already on LaunchClassLoader's classpath). LabyMod ships kotlin-stdlib/reflect + fastutil (caught by onCp) but NOT
    // kotlinx-coroutines/serialization/etc. — those LB-owned jars MUST be staged or LB's bootstrap NoClassDefFounds.
    private static boolean isLoaderProvided(String base){ String b=base.toLowerCase();
        return b.startsWith("asm-")||b.startsWith("asm.")||b.contains("sponge-mixin")||b.contains("mixinextras"); }

    // ---- acquire the live engine + register LB configs + parse LB AW --------------------------------------------------
    public void initMixin() throws Exception {
        // 1) parse LB's AccessWidener (applied by us to future classes; already-loaded go via INACC/AwReflect).
        byte[] awBytes = bundleResource("liquidbounce.accesswidener");
        if (awBytes == null) throw new IllegalStateException("liquidbounce.accesswidener not readable");
        aw = Class.forName("vspike.AccessWidener").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(awBytes));
        awApply = aw.getClass().getMethod("apply", String.class, byte[].class);

        // 2) the VALID Mixin engine + service, resolved THROUGH the LaunchClassLoader.
        service = Class.forName("org.spongepowered.asm.service.MixinService", false, lcl).getMethod("getService").invoke(null);
        mTransformExceptProxy = findUp(service.getClass(), "transformExceptMixinProxy", String.class, byte[].class);
        if (mTransformExceptProxy != null) mTransformExceptProxy.setAccessible(true);

        // 3) register LB configs into the live engine, downgrade to required:false (already-loaded targets otherwise
        //    throw MixinTargetAlreadyLoadedException), same as Fabric.
        Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", false, lcl);
        Method addConfig = mixins.getMethod("addConfiguration", String.class);
        // BOTH configs, exactly like VanillaPlatform: the loader-specific companion (liquidbounce-fabric) carries the
        // anchors that CALL LB's @Unique render hooks (e.g. MixinHud.liquid_bounce$extractOverlay -> OverlayRenderEvent
        // -> the CEF browser blit). Without it the UI never renders. LabyMod runs Mojmap vanilla MC, so the fabric
        // companion's vanilla-targeting mixins bind; any genuinely fabric-only ones skip cleanly.
        for (String cfg : new String[]{"liquidbounce.mixins.json", "liquidbounce-fabric.mixins.json"}) addConfig.invoke(null, cfg);
        int downgraded = 0;
        for (Object cfg : (Collection<?>) mixins.getMethod("getConfigs").invoke(null)) {
            String name = (String) cfg.getClass().getMethod("getName").invoke(cfg);
            if (name == null || !name.contains("liquidbounce")) continue;
            Object mixinConfig = cfg.getClass().getMethod("getConfig").invoke(cfg);
            Field req = mixinConfig.getClass().getDeclaredField("required"); req.setAccessible(true); req.setBoolean(mixinConfig, false);
            downgraded++;
        }
        if (downgraded == 0) throw new IllegalStateException("no LB Config found via Mixins.getConfigs() (Mixin API drift or config not registered)");
        InjectionLogger.info("registered "+downgraded+" LB mixin config(s), downgraded to required:false");

        // 4) acquire the live transformer from the LaunchWrapper Mixin Proxy (getActiveTransformer() is null on LabyMod).
        transformer = extractTransformerFromProxy();
        if (transformer == null) throw new IllegalStateException("could not obtain live IMixinTransformer from the LaunchWrapper Mixin Proxy");
        ClassLoader trLoader = transformer.getClass().getClassLoader();
        Class<?> iTr = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, trLoader);
        Class<?> meCls = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", false, trLoader);
        mTransformClassBytes = iTr.getMethod("transformClassBytes", String.class, String.class, byte[].class); mTransformClassBytes.setAccessible(true);
        mGenerateClass = iTr.getMethod("generateClass", meCls, String.class); mGenerateClass.setAccessible(true);
        env = meCls.getMethod("getCurrentEnvironment").invoke(null);
        Field fProc = findFieldUp(transformer.getClass(), "processor"); fProc.setAccessible(true); processor = fProc.get(transformer);
        fTransformedCount = findFieldUp(processor.getClass(), "transformedCount"); fTransformedCount.setAccessible(true);
        InjectionLogger.info("acquired live LabyMod transformer "+transformer.getClass().getName());

        // 5) CONFIG ISOLATION. LabyMod shares this MixinProcessor with its own configs (26.2-labymod4, modcompat) which
        //    are ALREADY applied to the loaded classes. Re-running them (a transformedCount reset) re-applies them onto
        //    already-mixed bytecode -> InvalidInjectionException. Instead: prepare LB's config once via select() (which
        //    parses pending configs WITHOUT applying to any class), then transform() swaps processor.configs to LB-only
        //    per call so ONLY LB is applied onto the loaded (LabyMod+Volt) schema, and restores the full list after.
        fConfigs = findFieldUp(processor.getClass(), "configs"); fConfigs.setAccessible(true);
        Method mSelect = findUp(processor.getClass(), "select", meCls); mSelect.setAccessible(true);
        fTransformedCount.setInt(processor, 0);                 // arm select
        mSelect.invoke(processor, env);                         // pending LB config -> configs (prepared, not applied)
        fTransformedCount.setInt(processor, 1);                 // keep >0 so checkSelect stays a no-op during our passes
        @SuppressWarnings("unchecked") java.util.List<Object> cfgs = (java.util.List<Object>) fConfigs.get(processor);
        allConfigs = new java.util.ArrayList<>(cfgs);
        for (Object c : cfgs) { String nm = configName(c); if (nm != null && nm.contains("liquidbounce")) lbConfigs.add(c); }
        if (lbConfigs.isEmpty()) throw new IllegalStateException("no liquidbounce MixinConfig in processor.configs after select(); configs="+allConfigs.size());
        InjectionLogger.info("config isolation ready: "+allConfigs.size()+" total configs, "+lbConfigs.size()+" LB config(s) isolated for per-target application");
    }

    /** The Mixin Proxy (a LaunchWrapper IClassTransformer in LaunchClassLoader.getTransformers()) wraps the shared
     *  MixinTransformer. Pull it out via its `transformer` field (static or instance across Mixin versions). */
    private Object extractTransformerFromProxy() throws Exception {
        Method getTransformers = findUp(lcl.getClass(), "getTransformers"); getTransformers.setAccessible(true);
        Object list = getTransformers.invoke(lcl);
        for (Object t : (Collection<?>) list) {
            Object proxy = t;
            // LabyMod may wrap the IClassTransformer in a LegacyTransformerAdapter — unwrap to the delegate.
            Method getDelegate = declared(t.getClass(), "getDelegate");
            if (getDelegate != null) { getDelegate.setAccessible(true); try { Object d = getDelegate.invoke(t); if (d != null) proxy = d; } catch (Throwable ignore) {} }
            if (!proxy.getClass().getName().equals("org.spongepowered.asm.mixin.transformer.Proxy")) continue;
            for (Class<?> k = proxy.getClass(); k != null; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (!f.getType().getName().contains("Transformer")) continue;
                    f.setAccessible(true);
                    Object v = f.get(Modifier.isStatic(f.getModifiers()) ? null : proxy);
                    if (v != null && !v.getClass().getName().endsWith("Proxy")) return v;
                }
            }
        }
        return null;
    }

    // ---- baselines: the ACTUAL loaded bytecode (LabyMod's Sponge mixins AND its Volt @Insert fields already applied).
    //  Re-deriving from pre-mixin bytes is unsafe on LabyMod: its Volt configs are one-shot (consumed at boot;
    //  Mixins.getConfigs()==0 before we add LB), so a transformedCount reset re-selects ONLY LB and drops the Volt
    //  fields — leaving X missing fields that the loaded class (and O) have. Snapshotting the loaded bytes via
    //  Instrumentation makes O == what retransformClasses will actually operate on, and X = O + LB only. -----------------
    public void prepareBaselines(java.util.List<String> internalTargets) throws Exception {
        if (lbActivated) throw new IllegalStateException("prepareBaselines after LB already activated");
        Set<String> want = new HashSet<>(internalTargets);
        Map<String,Class<?>> loadedCls = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) { String in = c.getName().replace('.','/'); if (want.contains(in)) loadedCls.putIfAbsent(in, c); }
        java.lang.instrument.ClassFileTransformer cap = new java.lang.instrument.ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String name, Class<?> cbc, ProtectionDomain pd, byte[] buf) {
                if (name != null && want.contains(name) && buf != null) baseline.put(name, buf.clone());
                return null;   // capture only, never modify
            }
        };
        inst.addTransformer(cap, true);
        try {
            Class<?>[] arr = loadedCls.values().toArray(new Class<?>[0]);
            if (arr.length > 0) inst.retransformClasses(arr);
        } catch (Throwable t) { InjectionLogger.warn("loaded-byte snapshot retransform failed: "+FullInjectAgent.rootMsg(t)); }
        finally { inst.removeTransformer(cap); }
        InjectionLogger.info("snapshotted "+baseline.size()+" loaded target baselines (LabyMod mixins+Volt applied)");
    }

    @SuppressWarnings("unchecked")
    public byte[] transform(String dotted, byte[] originalO) throws Exception {
        String internal = dotted.replace('.','/');
        // Base = the LOADED bytes (LabyMod's mixins + Volt already applied), AW-widened so LB's mixins can bind. Apply
        // ONLY LB (configs swapped to LB-only) so LabyMod's mixins are NOT re-applied -> X = loaded + LB.
        byte[] base = originalO != null ? originalO : baseline.get(internal);
        if (base == null) base = rawBytes(internal);
        if (base == null) return null;
        byte[] in = applyAw(internal, base); if (in == null) in = base;
        lbActivated = true;
        byte[] X;
        java.util.List<Object> cfgs = (java.util.List<Object>) fConfigs.get(processor);
        cfgs.clear(); cfgs.addAll(lbConfigs);                   // LB-only (common + fabric companion) for this pass
        fTransformedCount.setInt(processor, 1);                 // checkSelect no-op (pendingConfigs empty)
        try { X = (byte[]) mTransformClassBytes.invoke(transformer, dotted, dotted, in); }
        catch (Throwable t) {
            // An individual LB mixin that cannot bind against a LabyMod-reshaped method (e.g. LVTGeneratorError) must not
            // abort the whole injection — skip just this hook, LB runs minus it.
            Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
            InjectionLogger.warn("skip LB hook on "+internal+" (mixin apply failed: "+c.getClass().getSimpleName()+": "+String.valueOf(c.getMessage()).split("\n")[0]+")");
            return null;
        }
        finally { cfgs.clear(); cfgs.addAll(allConfigs); }      // restore full list — LabyMod's live engine intact
        if (X == null) return null;
        // Compare against the AW-widened INPUT: LB's AW rewrites access flags, so a diff vs raw base would be non-empty
        // even when LB added nothing. X == in => LB contributed nothing => skip. Converter still gets O = loaded base.
        X = FabricPlatform.alignSynthetics(in, X);
        return Arrays.equals(X, in) ? null : X;
    }

    public byte[] generateClass(String dotted){ try { return (byte[]) mGenerateClass.invoke(transformer, env, dotted); } catch(Throwable t){ return null; } }

    /** Pre-mixin bytes: LabyMod's non-mixin transformers (its AW etc.) applied to raw, then LB's OWN AW applied so LB's
     *  mixins can bind against widened members. Fallback to LabyMod's transformExceptMixinProxy or raw if unavailable. */
    private byte[] preMixinBytes(String internal){ try {
        byte[] raw = rawBytes(internal); if (raw == null) return null;
        byte[] labyBase = raw;
        if (mTransformExceptProxy != null) { try { Object r = mTransformExceptProxy.invoke(service, internal.replace('/','.'), raw); if (r != null) labyBase = (byte[]) r; } catch (Throwable ignore) {} }
        byte[] widened = applyAw(internal, labyBase);
        return widened != null ? widened : labyBase;
    } catch (Throwable t){ return null; } }

    public Object accessWidener(){ return aw; }
    public boolean cftAppliesAw(){ return true; }
    public byte[] applyAw(String internalName, byte[] bytes){ try { return (byte[]) awApply.invoke(aw, internalName, bytes); } catch(Throwable t){ return null; } }

    public boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd){ try {
        dc().invoke(lcl, dotted, bytes, 0, bytes.length, pd);
        return true;
    } catch(Throwable t){ Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
        String m = String.valueOf(c.getMessage());
        if (c instanceof LinkageError && (m.contains("duplicate")||m.contains("attempted  duplicate"))) return true;
        InjectionLogger.error("DEFINE-FAIL "+dotted+" -> "+c.getClass().getSimpleName()+": "+c.getMessage());
        return false; } }

    // baseline O for a target = X_others (LabyMod mixins); for non-targets = raw (no LB mixin targets them).
    public byte[] originalBytes(String internalName){ byte[] b = baseline.get(internalName); return b != null ? b : rawBytes(internalName); }

    public byte[] bundleResource(String path){
        try (InputStream in = LabyModPlatform.class.getClassLoader().getResourceAsStream(path)) { if (in != null) return in.readAllBytes(); } catch(Throwable t){}
        try (InputStream in = lcl.getResourceAsStream(path)) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; }
    }

    private byte[] rawBytes(String internal){ try { Object b = mGetClassBytes.invoke(lcl, internal.replace('/','.')); return (byte[]) b; } catch(Throwable t){ return null; } }

    /** MixinConfig is a package-private class; getName() is public but its declaring class isn't accessible, so the
     *  Method must be forced accessible before invoke. */
    private static String configName(Object mixinConfig){ try {
        Method gn = mixinConfig.getClass().getMethod("getName"); gn.setAccessible(true);
        return (String) gn.invoke(mixinConfig);
    } catch (Throwable t){ return null; } }

    // ---- reflection helpers -----------------------------------------------------------------------------------------
    private static Method findUp(Class<?> c, String name, Class<?>... ps){ for (Class<?> k=c;k!=null;k=k.getSuperclass()){ Method m=declaredM(k,name,ps); if(m!=null) return m; } return null; }
    private static Method declared(Class<?> k, String name){ try { return k.getDeclaredMethod(name); } catch(NoSuchMethodException e){ return null; } }
    private static Method declaredM(Class<?> k, String name, Class<?>... ps){ try { return k.getDeclaredMethod(name, ps); } catch(NoSuchMethodException e){ return null; } }
    private static Field findFieldUp(Class<?> c, String name){ for (Class<?> k=c;k!=null;k=k.getSuperclass()){ try { return k.getDeclaredField(name); } catch(NoSuchFieldException e){} } throw new RuntimeException("field "+name+" not found on "+c.getName()); }
    private static RuntimeException rt(String where, Throwable t){ Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
        return new IllegalStateException("LabyModPlatform."+where+" failed: "+c.getClass().getSimpleName()+": "+c.getMessage(), c); }
}
