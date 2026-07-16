import org.spongepowered.asm.mixin.Mixins;                                  // in the agent bundle; at runtime resolves to Knot's (shared, parent-delegated)
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
import java.io.*; import java.lang.instrument.Instrumentation; import java.lang.reflect.*;
import java.nio.file.*; import java.security.*; import java.util.*; import java.util.jar.*;
import lbrt.InjectionLogger;

/** Fabric (Knot) late-attach platform.
 *
 *  Cross-loader identity (Option B): the converter classes run on the SYSTEM loader (agent mechanism), but Sponge Mixin
 *  and ASM are on the process classpath and Knot delegates those packages to its parent, so they share one identity
 *  with the agent. Every Knot/Fabric/Mixin seam is therefore driven through REFLECTION on Object handles (the live
 *  transformer object is a Knot-visible MixinTransformer that we never cast to a system-loader type), and LB + its
 *  runtime helpers (lbrt.*) resolve on Knot via parent delegation — a single lbrt.Platform identity for agent + LB.
 *
 *  X-acquisition workaround (proven by the probe): a post-boot Mixins.addConfiguration is only applied by a direct
 *  transformClassBytes when (1) the LB configs are downgraded to required:false (else MixinTargetAlreadyLoadedException
 *  on already-loaded targets) and (2) MixinProcessor.transformedCount is reset to 0 before the transform (forces
 *  checkSelect -> select -> prepareConfigs to promote the pending config). Both are asserted; a Mixin version drift
 *  fails loud. */
final class FabricPlatform implements LoaderPlatform {
    private ClassLoader knot;               // KnotClassLoader (owns net.minecraft.* + staged LB)
    private Object launcher;                // FabricLauncher (Knot)
    private Object delegate;                // KnotClassDelegate (getRawClassBytes / getPreMixinClassBytes)
    private Object transformer;             // IMixinTransformer impl (Knot-visible; Object handle only)
    private Object processor;               // MixinProcessor (transformedCount reset)
    private Field  fTransformedCount;       // MixinProcessor.transformedCount (private int)
    private Method mTransformClassBytes;    // IMixinTransformer.transformClassBytes(String,String,byte[])
    private Method mGenerateClass;          // IMixinTransformer.generateClass(MixinEnvironment,String)
    private Object env;                     // MixinEnvironment.getCurrentEnvironment()
    private Method mGetRawBytes, mGetPreMixinBytes; // on the delegate
    private Method mDefineClassFwd;         // KnotClassLoader.defineClassFwd(String,byte[],int,int,CodeSource)
    private Method mAddToClassPath;         // FabricLauncher.addToClassPath(Path,String...)
    private Object aw;                      // vspike.AccessWidener (parsed as DATA for INACC/Resolver)
    private File agentJar;
    private final Map<String,byte[]> baseline = new HashMap<>();   // internal -> X_others (all mods except LB) = loaded schema
    private boolean lbActivated;                                   // has the transformedCount reset selected LB's config yet

    // ---- §1a: KnotClassLoader ---------------------------------------------------------------------------------------
    public ClassLoader targetLoader(){ try {
        Class<?> flb = fc("net.fabricmc.loader.impl.launch.FabricLauncherBase");
        launcher = flb.getMethod("getLauncher").invoke(null);
        if (launcher == null) throw new IllegalStateException("FabricLauncherBase.getLauncher() == null; not a live Knot launch");
        knot = (ClassLoader) launcher.getClass().getMethod("getTargetClassLoader").invoke(launcher);
        mAddToClassPath = launcher.getClass().getMethod("addToClassPath", Path.class, String[].class);
        Method getDelegate = null;                                                 // package-private on KnotClassLoader
        for (Class<?> k = knot.getClass(); k != null && getDelegate == null; k = k.getSuperclass()) { try { getDelegate = k.getDeclaredMethod("getDelegate"); } catch(NoSuchMethodException e){} }
        if (getDelegate == null) throw new NoSuchMethodException("getDelegate() on "+knot.getClass().getName());
        getDelegate.setAccessible(true);
        delegate = getDelegate.invoke(knot);
        mGetRawBytes     = findByteMethod(delegate, "getRawClassBytes");
        mGetPreMixinBytes= findByteMethod(delegate, "getPreMixinClassBytes");
        mDefineClassFwd  = knot.getClass().getMethod("defineClassFwd", String.class, byte[].class, int.class, int.class, CodeSource.class);
        mDefineClassFwd.setAccessible(true);                                        // method is public but KnotClassLoader itself is a package-private class
        return knot;
    } catch (Throwable t){ throw rt("targetLoader (Knot acquisition)", t); } }

    // ---- §3.2: stage LB onto Knot (deps already on the stock classpath are skipped; ASM/Mixin never pushed) ----------
    public Path stageBundle(Instrumentation inst, File agentJar) throws Exception {
        this.agentJar = agentJar;
        exposeRuntimeToParent(agentJar);   // let Knot delegate lbrt.*/agent classes to the system loader (single identity)
        // basenames already on the process classpath -> pushing a second copy onto Knot would split class identity.
        Set<String> onCp = new HashSet<>();
        for (String p : System.getProperty("java.class.path","").split(File.pathSeparator)) { String b = new File(p).getName().toLowerCase(); if(!b.isEmpty()) onCp.add(b); }
        Path tmp = Files.createTempDirectory("lb-fabric-"); tmp.toFile().deleteOnExit();
        Path lbBundle = null; int pushed = 0, skipped = 0;
        try (JarFile jf = new JarFile(agentJar)) { for (var en = jf.entries(); en.hasMoreElements();) { JarEntry e = en.nextElement(); String n = e.getName();
            if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
            String base = new File(n).getName();
            boolean isLb = base.equals("liquidbounce.jar");
            if (!isLb && (onCp.contains(base.toLowerCase()) || isLoaderProvided(base))) { skipped++; continue; }   // fabric-api/kotlin/asm/mixin/... already present
            Path o = tmp.resolve(base);
            try (InputStream in = jf.getInputStream(e)) { Files.copy(in, o, StandardCopyOption.REPLACE_EXISTING); } o.toFile().deleteOnExit();
            if (isLb) { lbBundle = o; addToClassPath(o, "net.ccbluex"); }
            else addToClassPath(o);
            pushed++;
        } }
        if (lbBundle == null) throw new IllegalStateException("Bundled agent-libs/liquidbounce.jar not found");
        InjectionLogger.info("staged onto Knot: pushed "+pushed+" jar(s), skipped "+skipped+" already-present; Knot="+knot);
        return lbBundle;
    }
    /** Hard identity-split hazards Fabric provides itself — never push these to Knot even if not on the -cp scan. */
    private static boolean isLoaderProvided(String base){ String b=base.toLowerCase();
        return b.startsWith("asm-")||b.startsWith("asm.")||b.contains("sponge-mixin")||b.contains("mixinextras")||b.startsWith("fabric-loader"); }
    private void addToClassPath(Path jar, String... prefixes) throws Exception { mAddToClassPath.invoke(launcher, jar, prefixes); }

    // ---- expose the injector runtime to Knot via PARENT delegation (NOT a second Knot-owned copy) --------------------
    // Sidecars run on Knot but reference lbrt.* helpers (AwReflect/DuckDispatch/JoinGate), which the agent loaded on the
    // SYSTEM loader. Knot's dev-mode isolation refuses to resolve them for game classes ("hasn't been exposed to the
    // game") -> NoClassDefFoundError the first time a late-loaded Knot class (e.g. DisconnectedScreen) links one.
    // Whitelisting the agent jar as a valid PARENT code source makes Knot's tryLoadClass/loadClass delegate those
    // classes to its parent (system) loader via parentClassLoader.loadClass instead of throwing -> a SINGLE identity
    // shared with the agent. This DELEGATES, never defineClass's a Knot-owned copy (that is what addToClassPath would
    // do, forking the stateful helpers into two identities). lbrt.* is confirmed absent from every jar pushed onto
    // Knot, so Knot can never own a copy -> the single-identity property is structural, not incidental.
    //
    // Blast radius: the whitelist is code-source-granular, so in principle ANY agent-jar-root class Knot fails to find
    // on its own classpath would now parent-delegate rather than throw. In practice this is inert: the root also holds
    // the agent's private Mixin/ASM/MixinExtras/vspike/converter copies, but those packages are Knot-owned (Fabric
    // provides Mixin+ASM; isLoaderProvided refuses to push the agent's) so isValidParentUrl is never consulted for
    // them, and the converter/vspike/agent classes are never referenced by game classes. Accepted latent risk: a class
    // present in the agent jar but absent from Knot's (same-major) Mixin/ASM would cross-delegate silently rather than
    // fail loud. Narrowing to parentSourcedClasses was rejected: that path can re-define from parent bytes (a second
    // identity), which would defeat the whole point for JoinGate/DuckDispatch.
    @SuppressWarnings("unchecked")
    private void exposeRuntimeToParent(File agentJar) throws Exception {
        Path agentPath = normalizeParentPath(agentJar.toPath());
        Field f = null;
        for (Class<?> k = delegate.getClass(); k != null && f == null; k = k.getSuperclass()) {
            try { f = k.getDeclaredField("validParentCodeSources"); } catch (NoSuchFieldException e) { /* walk up */ }
        }
        if (f == null) throw new NoSuchFieldException("validParentCodeSources on " + delegate.getClass().getName());
        f.setAccessible(true);
        // We publish the new set via a single reference write and rely on Knot's field being volatile for the
        // happens-before to its concurrent isValidParentUrl readers. Verify that assumption instead of trusting it: if
        // a future Knot makes the field non-volatile, surface it loudly rather than risk a torn/stale read landmine.
        if (!java.lang.reflect.Modifier.isVolatile(f.getModifiers()))
            InjectionLogger.warn("Knot.validParentCodeSources is NOT volatile on " + f.getDeclaringClass().getName()
                + "; single-identity parent delegation depends on safe publication of this field on this Fabric version");
        Set<Path> cur = (Set<Path>) f.get(delegate);
        // Rebuild the whole set and publish once via the volatile field (safe concurrent read; never mutated after).
        Set<Path> next = new HashSet<>();
        if (cur != null) next.addAll(cur);
        boolean added = next.add(agentPath);
        f.set(delegate, next);
        InjectionLogger.info("exposed injector runtime to Knot via parent delegation (" + (added ? "added " : "already present ")
            + agentPath + "; validParentCodeSources=" + next.size() + ")");
    }
    /** Normalize the same way Knot stores valid-parent paths, so isValidParentUrl's Path comparison matches. */
    private static Path normalizeParentPath(Path p) {
        try { return (Path) Class.forName("net.fabricmc.loader.impl.util.LoaderUtil")
            .getMethod("normalizeExistingPath", Path.class).invoke(null, p); }
        catch (Throwable t) { try { return p.toRealPath(); } catch (Exception e) { return p.toAbsolutePath().normalize(); } }
    }

    // ---- §1c/§3.3: register LB configs into the LIVE Knot Mixin (required:false), apply AW to Knot, grab transformer -
    public void initMixin() throws Exception {
        // 1) AccessWidener onto the live Knot ClassTweaker (widens FUTURE classes; already-loaded go via AwReflect).
        byte[] awBytes = bundleResource("liquidbounce.accesswidener");
        if (awBytes == null) throw new IllegalStateException("liquidbounce.accesswidener not readable from Knot/agent");
        Object loaderImpl = fc("net.fabricmc.loader.impl.FabricLoaderImpl").getField("INSTANCE").get(null);
        Object classTweaker = loaderImpl.getClass().getMethod("getClassTweaker").invoke(loaderImpl);
        Class<?> ctrCls = fc("net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweakerReader");
        Method create = null; for (Method m : ctrCls.getMethods()) if (m.getName().equals("create") && m.getParameterCount()==1) { create=m; break; }
        Object reader = create.invoke(null, classTweaker);
        reader.getClass().getMethod("read", byte[].class, String.class).invoke(reader, awBytes, "official");
        InjectionLogger.info("applied liquidbounce.accesswidener to live Knot ClassTweaker ("+awBytes.length+" bytes)");

        // 2) register LB's mixin configs into the live service, then downgrade them to required:false (probe workaround #1).
        Mixins.addConfiguration("liquidbounce.mixins.json");
        Mixins.addConfiguration("liquidbounce-fabric.mixins.json");
        int downgraded = 0;
        for (Object cfg : Mixins.getConfigs()) {                                   // org.spongepowered.asm.mixin.transformer.Config
            String name = (String) cfg.getClass().getMethod("getName").invoke(cfg);
            if (name == null || !name.contains("liquidbounce")) continue;
            Object mixinConfig = cfg.getClass().getMethod("getConfig").invoke(cfg); // IMixinConfig -> MixinConfig
            Field req = mixinConfig.getClass().getDeclaredField("required"); req.setAccessible(true); req.setBoolean(mixinConfig, false);
            downgraded++;
        }
        if (downgraded == 0) throw new IllegalStateException("required:false workaround failed: no LB Config found via Mixins.getConfigs() (Mixin API drift)");
        InjectionLogger.info("registered "+downgraded+" LB mixin config(s), downgraded to required:false");

        // 3) acquire the LIVE transformer + processor.transformedCount + method handles (all Knot-visible, via reflection).
        Class<?> svcKnot = fc("net.fabricmc.loader.impl.launch.knot.MixinServiceKnot");
        Method getTr = svcKnot.getDeclaredMethod("getTransformer"); getTr.setAccessible(true);
        transformer = getTr.invoke(null);
        if (transformer == null) throw new IllegalStateException("MixinServiceKnot.getTransformer() == null");
        ClassLoader trLoader = transformer.getClass().getClassLoader();
        Class<?> iTr = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, trLoader);
        Class<?> meCls = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", false, trLoader);
        mTransformClassBytes = iTr.getMethod("transformClassBytes", String.class, String.class, byte[].class); mTransformClassBytes.setAccessible(true);
        mGenerateClass = iTr.getMethod("generateClass", meCls, String.class); mGenerateClass.setAccessible(true);
        env = meCls.getMethod("getCurrentEnvironment").invoke(null);
        Field fProc = transformer.getClass().getDeclaredField("processor"); fProc.setAccessible(true); processor = fProc.get(transformer);
        fTransformedCount = processor.getClass().getDeclaredField("transformedCount"); fTransformedCount.setAccessible(true);
        InjectionLogger.info("acquired live Knot transformer "+transformer.getClass().getName()+" (env="+env+")");
    }

    // ---- §1c: baselines (X_others) captured while LB is UNVISITED, then LB activated -------------------------------
    public void prepareBaselines(java.util.List<String> internalTargets) throws Exception {
        if (lbActivated) throw new IllegalStateException("prepareBaselines after LB already activated");
        int n = 0;
        for (String internal : internalTargets) {
            byte[] pre = getPreMixinClassBytes(internal); if (pre == null) continue;
            byte[] xOthers;
            try { xOthers = (byte[]) mTransformClassBytes.invoke(transformer, internal.replace('/','.'), internal.replace('/','.'), pre); } // LB unvisited -> other mods only
            catch (Throwable t) {
                // Another mod's mixin whose injection point diverges on this already-loaded target throws here. Either
                // way it is logged. Default is FAIL-FAST: log an error naming the target + abort. With
                // tolerateBaselineFailures=true, log a warning and skip just this target (its transform() then falls back
                // to originalO) so one bad mod mixin doesn't kill the whole attach.
                Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                String head = internal+" -> "+c.getClass().getSimpleName()+": "+String.valueOf(c.getMessage()).split("\n")[0];
                if (!FullInjectAgent.tolerateBaselineFailures) {
                    InjectionLogger.error("baseline capture FAILED for "+head+" — aborting injection (enable tolerateBaselineFailures / tick 'Tolerate other-mod mixin failures' to skip it and continue)");
                    if (t instanceof Error e) throw e; if (t instanceof Exception e) throw e; throw new RuntimeException(t);
                }
                InjectionLogger.warn("baseline capture skipped (tolerated) for "+head);
                continue;
            }
            baseline.put(internal, xOthers == null ? pre : xOthers); n++;
        }
        InjectionLogger.info("captured "+n+" non-LB mod baselines (X_others)");
    }

    // ---- §1c: X for an already-loaded target = X_all (X_others + LB) after forcing LB's config to select --------------
    public byte[] transform(String dotted, byte[] originalO) throws Exception {
        String internal = dotted.replace('.','/');
        byte[] in = getPreMixinClassBytes(internal);
        if (in == null) in = originalO;                                            // fall back to the raw baseline
        fTransformedCount.setInt(processor, 0);                                    // probe workaround #2: promote LB's pending config
        lbActivated = true;
        byte[] X = (byte[]) mTransformClassBytes.invoke(transformer, dotted, dotted, in);
        if (X == null) return null;
        byte[] base = originalO != null ? originalO : baseline.get(internal);      // X_others baseline
        if (base != null) X = alignSynthetics(base, X);                            // renumber other mods' counter-suffixed synthetics back to the baseline's names
        return (base != null && Arrays.equals(X, base)) ? null : X;               // isolate LB's delta
    }

    /** MixinExtras (and other injectors) name their synthetic bridges `...$<methods.size()>`; that counter is a global
     *  post-pass over the fully-assembled class, so LB's added methods shift EVERY other mod's bridge suffix between the
     *  no-LB baseline (X_others) and the with-LB output (X_all). Re-map X_all's non-LB counter-suffixed synthetics back
     *  to the baseline's names (matched by name-without-trailing-$digits + descriptor) so the converter's O-vs-X diff
     *  isolates only LB's real additions. LB's own synthetics keep their (unmatched) names and go to the sidecar. */
    static byte[] alignSynthetics(byte[] xOthers, byte[] xAll) {
        ClassNode co = read(xOthers), ca = read(xAll);
        Set<String> keysO = new HashSet<>(), keysA = new HashSet<>();
        for (MethodNode m : co.methods) keysO.add(m.name+" "+m.desc);
        for (MethodNode m : ca.methods) keysA.add(m.name+" "+m.desc);
        Map<String,ArrayDeque<String>> oNorm = new HashMap<>();                    // (normName+" "+desc) -> baseline names available to claim
        for (MethodNode m : co.methods) if (!keysA.contains(m.name+" "+m.desc) && isCounterSynthetic(m.name))
            oNorm.computeIfAbsent(norm(m.name)+" "+m.desc, k -> new ArrayDeque<>()).add(m.name);
        Map<String,String> rename = new HashMap<>();                              // X_all name -> baseline name
        for (MethodNode m : ca.methods) { String k=m.name+" "+m.desc; if (keysO.contains(k) || !isCounterSynthetic(m.name)) continue;
            ArrayDeque<String> dq = oNorm.get(norm(m.name)+" "+m.desc); if (dq!=null && !dq.isEmpty()) rename.put(m.name, dq.poll()); }
        if (rename.isEmpty()) return xAll;
        for (MethodNode m : ca.methods) {
            String nn = rename.get(m.name); if (nn != null) m.name = nn;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode mi && ca.name.equals(mi.owner)) { String r=rename.get(mi.name); if (r!=null) mi.name=r; }
                else if (insn instanceof InvokeDynamicInsnNode id) {
                    for (int i=0;i<id.bsmArgs.length;i++) if (id.bsmArgs[i] instanceof Handle h && ca.name.equals(h.getOwner())) {
                        String r=rename.get(h.getName()); if (r!=null) id.bsmArgs[i]=new Handle(h.getTag(),h.getOwner(),r,h.getDesc(),h.isInterface()); }
                }
            }
        }
        ClassWriter cw = new ClassWriter(0); ca.accept(cw); return cw.toByteArray();
    }
    private static ClassNode read(byte[] b){ ClassNode cn=new ClassNode(); new ClassReader(b).accept(cn,0); return cn; }
    private static final String[] SYN_MARKERS = {"mixinextras","wrapOperation","$bridge$","$wrapped$","handler$","redirect$","modify$","inject$"};
    /** An injector synthetic whose name carries one or more size()-based counter segments (which shift when LB adds
     *  methods). Restricted to names bearing a known injector marker so ordinary lambdas ($N) are never re-mapped. */
    private static boolean isCounterSynthetic(String name){ boolean marked=false; for (String s:SYN_MARKERS) if (name.contains(s)){ marked=true; break; }
        return marked && !norm(name).equals(name); }
    /** Replace every whole `$<digits>` segment with `$#` so both counter positions in `...$wrapped$171$172` normalize. */
    private static String norm(String name){ return name.replaceAll("\\$\\d+(?=\\$|$)", "\\$#"); }
    public byte[] generateClass(String dotted){ try { return (byte[]) mGenerateClass.invoke(transformer, env, dotted); } catch(Throwable t){ return null; } }

    public Object accessWidener(){ try {
        if (aw == null) { byte[] b = bundleResource("liquidbounce.accesswidener");
            aw = Class.forName("vspike.AccessWidener").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(b)); }
        return aw;
    } catch(Throwable t){ throw rt("accessWidener parse", t); } }

    public boolean cftAppliesAw(){ return false; }                                 // Knot's ClassTweaker widens future classes
    public byte[] applyAw(String internalName, byte[] bytes){ return null; }        // unused on Fabric (cftAppliesAw==false)

    // ---- §1d: define sidecar/state/synthetic into Knot via the public defineClassFwd -------------------------------
    public boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd){ try {
        CodeSource cs = pd == null ? null : pd.getCodeSource();
        mDefineClassFwd.invoke(knot, dotted, bytes, 0, bytes.length, cs);
        return true;
    } catch(Throwable t){ Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
        String m = String.valueOf(c.getMessage());
        if (c instanceof LinkageError && m.contains("duplicate")) return true;      // already defined -> fine
        if (c.getClass().getSimpleName().contains("LinkageError") && m.contains("duplicate class")) return true;
        InjectionLogger.error("DEFINE-FAIL "+dotted+" -> "+c.getClass().getSimpleName()+": "+c.getMessage());
        return false; } }

    // ---- §1b: schema baseline O. For a mixin target = X_others (loaded schema: all non-LB mods). For everything else
    //           (hierarchy metadata over arbitrary classes) = raw Knot bytes (no LB mixin targets them, so X_others==raw). -
    public byte[] originalBytes(String internalName){ byte[] b = baseline.get(internalName); return b != null ? b : getRawClassBytes(internalName); }

    public byte[] bundleResource(String path){
        // agent-jar-root resources (lb-mixin-targets.txt) resolve on the system loader; LB-jar resources resolve on Knot.
        try (InputStream in = FabricPlatform.class.getClassLoader().getResourceAsStream(path)) { if (in != null) return in.readAllBytes(); } catch(Throwable t){}
        try (InputStream in = knot.getResourceAsStream(path)) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; }
    }

    // ---- reflection helpers -----------------------------------------------------------------------------------------
    private byte[] getRawClassBytes(String internal){ try { return (byte[]) mGetRawBytes.invoke(delegate, internal); } catch(Throwable t){ return null; } }
    private byte[] getPreMixinClassBytes(String internal){ try { return (byte[]) mGetPreMixinBytes.invoke(delegate, internal); } catch(Throwable t){ return null; } }
    private static Class<?> fc(String dotted) throws ClassNotFoundException { return Class.forName(dotted); }
    private static Method findByteMethod(Object target, String name){
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Method m = declared(k, name); if (m != null) { m.setAccessible(true); return m; }
            for (Class<?> ifc : k.getInterfaces()) { m = declared(ifc, name); if (m != null) { m.setAccessible(true); return m; } }
        }
        throw new IllegalStateException("byte method "+name+" not found on "+target.getClass().getName());
    }
    private static Method declared(Class<?> k, String name){ try { return k.getDeclaredMethod(name, String.class); } catch(NoSuchMethodException e){ return null; } }
    private static RuntimeException rt(String where, Throwable t){ Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
        return new IllegalStateException("FabricPlatform."+where+" failed: "+c.getClass().getSimpleName()+": "+c.getMessage(), c); }
}
