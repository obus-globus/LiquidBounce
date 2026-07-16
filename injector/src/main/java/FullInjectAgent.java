import org.objectweb.asm.ClassReader;
import java.io.*; import java.lang.instrument.*; import java.lang.reflect.Method;
import java.nio.file.*; import java.security.ProtectionDomain; import java.util.*; import java.util.concurrent.*; import java.util.jar.*;
import lbrt.InjectionLogger;

/** Attach to a fully-running client and inject full LiquidBounce. Uniform-convert every mixin target
 *  (already-loaded -> retransform; future -> on-load CFT returns target'); rewrite LB callers to sidecars;
 *  then manually kick LB's ClientStartEvent so it initializes on the already-running game. */
public class FullInjectAgent {
    static LoaderPlatform PLATFORM;                                               // loader-specific seams (vanilla/Fabric/NeoForge)
    static ClassLoader SYS;                                                       // == PLATFORM.targetLoader(); holds net.minecraft.* + staged LB
    static final boolean DEBUG = Boolean.getBoolean("lb.agent.debug");
    static final Set<String> definedSynth = ConcurrentHashMap.newKeySet();
    static final Set<String> definingSynth = ConcurrentHashMap.newKeySet();
    static final Map<String,Conv> convMap = new ConcurrentHashMap<>();             // internal -> conversion holder
    static final Map<String,List<String[]>> ifaceMap = new ConcurrentHashMap<>();  // iface -> all [target, sidecar]; read by concurrent CFT threads
    static final Set<String> targetSet = new HashSet<>();                          // internal names of mixin targets
    static Instrumentation INST;
    static volatile java.lang.instrument.ClassFileTransformer CFT;                 // installed on-load transformer; kept so uninject can remove it
    static volatile boolean everInjected;                                         // true once injected; a second inject in the same JVM (even after uninject) is refused — LB can't be re-staged live. volatile: successive attaches may land on different attach-listener threads.
    static final Set<String> preLoaded = ConcurrentHashMap.newKeySet();            // MC classes loaded at attach (can't be AW-widened)
    static final Map<String,Boolean> npField = new ConcurrentHashMap<>();          // owner#name -> non-public?
    static final Map<String,Boolean> npMethod = new ConcurrentHashMap<>();         // owner#name desc -> non-public?
    static final Set<String> INACC = ConcurrentHashMap.newKeySet();                // already-loaded package-private MC classes LB references by type
    static final Set<String> preBootstrapLb = ConcurrentHashMap.newKeySet();       // LB classes present before normal on-load caller rewriting
    /** per-target conversion: target' bytes + sidecar/state (defined LAZILY in the CFT with the target's real PD). */
    static final class Conv { byte[] target, sidecar, state; String sidecarName, stateName; volatile boolean defined;
        boolean define(ProtectionDomain pd){ synchronized(this){ if(defined) return true;
            boolean ok = defineSynthetics(target, pd) & defineSynthetics(sidecar, pd)
                       & FullInjectAgent.define(stateName.replace('/','.'), state, pd)
                       & FullInjectAgent.define(sidecarName.replace('/','.'), sidecar, pd);
            if(ok) defined=true;
            else LateAttachVerifier.error("SIDECAR_DEFINE_FAILURE",sidecarName,"sidecar","One or more synthetic/state/sidecar definitions failed");
            return ok; } } }

    public static void agentmain(String a, Instrumentation inst) throws Exception {
        InjectionLogger.configure(a);
        if ("uninject".equals(InjectionLogger.argValue(a, "mode"))) { INST = inst;
            try { uninject(inst); } catch (Throwable t) { InjectionLogger.error("uninject failed", t); throw t; }
            return; }
        if (everInjected) { InjectionLogger.error("re-injection refused: LiquidBounce was already injected into this JVM. Re-injecting after an uninject is not supported (its runtime cannot be re-staged onto the already-populated loader). Restart the client to inject again.");
            throw new IllegalStateException("re-injection after uninject is not supported in the same JVM; restart the client"); }
        INST = inst;
        PLATFORM = detectPlatform(inst);
        SYS = PLATFORM.targetLoader();
        lbrt.Platform.LOADER = SYS;
        applyDataDir(a);
        verifyAsmRuntime();
        for (Class<?> c : inst.getAllLoadedClasses()) preLoaded.add(c.getName().replace('.','/'));   // snapshot BEFORE we load anything
        inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(), Map.of("java.lang", Set.of(FullInjectAgent.class.getModule())), Set.of(), Map.of());
        RetransformConverter.CLASS_BYTES = PLATFORM::originalBytes;
        InjectionLogger.info("staging LB bundle ("+preLoaded.size()+" classes already loaded)");
        File self = new File(FullInjectAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path lbBundle = PLATFORM.stageBundle(inst, self);

        PLATFORM.initMixin();
        Object aw = PLATFORM.accessWidener();
        // Inaccessible types: AW-widened classes that are ALREADY loaded (so can't be widened) and still non-public.
        // LB references these by type; the CFT erases those references to Object + reflection.
        try { var caF = aw.getClass().getDeclaredField("classAccessible"); caF.setAccessible(true);
            @SuppressWarnings("unchecked") Set<String> ca = (Set<String>) caF.get(aw);
            for (String cn : ca) { if (!preLoaded.contains(cn)) continue; try { Class<?> k = Class.forName(cn.replace('/','.'), false, SYS); if (!java.lang.reflect.Modifier.isPublic(k.getModifiers())) INACC.add(cn); } catch(Throwable t){} }
            InjectionLogger.info("inaccessible already-loaded AW classes: "+INACC.size()+" "+INACC);
        } catch(Throwable t){ InjectionLogger.warn("INACC compute failed -> "+rootMsg(t)); }

        List<String> targets = new ArrayList<>();
        try (var r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(PLATFORM.bundleResource("lb-mixin-targets.txt"))))) { String l; while ((l=r.readLine())!=null) if(!l.isBlank()) targets.add(l.trim().replace('.','/')); }
        targetSet.addAll(targets);
        PLATFORM.prepareBaselines(targets);   // Fabric: capture the non-LB mod baseline before LB's config is selected

        // Convert transactionally. Nothing is published/defined until every transformed target verifies, because the
        // global relocation tables permit cross-target sidecar calls and therefore cannot be safely partially accepted.
        Map<String,Class<?>> loadedMap = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) loadedMap.put(c.getName().replace('.','/'), c);
        // Phase A: mixin-transform every target and collect the GLOBAL added-method table (so cross-target / base-class
        // @Unique calls route to the right sidecar during conversion).
        LinkedHashMap<String,byte[][]> txMap = new LinkedHashMap<>();
        Map<String,String[]> gadded = new HashMap<>(); Map<String,String[]> gfield = new HashMap<>(); Map<String,List<String[]>> giface = new HashMap<>();
        List<String> phaseFailures = new ArrayList<>();
        for (String internal : targets) { try {
            byte[] O = PLATFORM.originalBytes(internal); if(O==null) continue;
            byte[] X = PLATFORM.transform(internal.replace('/','.'), O); if (X==null) continue;
            txMap.put(internal, new byte[][]{O, X}); RetransformConverter.collectAdded(internal, O, X, gadded, gfield, giface);
        } catch (Throwable e) { phaseFailures.add(internal); LateAttachVerifier.error("MIXIN_TRANSFORM_FAILURE",internal,"target",rootMsg(e)); InjectionLogger.error("transform fail "+internal+" -> "+rootMsg(e)); } }
        RetransformConverter.GADDED = gadded; RetransformConverter.GFIELD = gfield; RetransformConverter.GIFACE = giface;
        InjectionLogger.info("phase A: "+txMap.size()+" transformed; global added-methods="+gadded.size()+" added-fields="+gfield.size());
        // Phase B: stage every conversion against the complete global tables.
        int conv=0, eager=0; List<String> skipped=new ArrayList<>();
        LinkedHashMap<String,Conv> pendingConv=new LinkedHashMap<>();
        Map<String,List<String[]>> pendingIfaces=new HashMap<>();
        for (var e : txMap.entrySet()) { String internal = e.getKey(); try {
            byte[] O = e.getValue()[0], X = e.getValue()[1];
            RetransformConverter c = new RetransformConverter(internal); RetransformConverter.Result res = c.run(O, X);
            Conv cv = new Conv(); cv.target=res.target; cv.sidecar=res.sidecar; cv.state=c.stateBytes(); cv.sidecarName=res.sidecarName; cv.stateName=c.stateName();
            if (!LateAttachVerifier.verifyConversion(internal, O, cv.target, cv.sidecar, cv.state, gadded, gfield, giface)) {
                skipped.add(internal); continue;
            }
            for (String di : res.droppedInterfaces) {
                List<String[]> impls = pendingIfaces.computeIfAbsent(di, k -> new ArrayList<>());
                boolean duplicate=false; for(String[] impl:impls) if(impl[0].equals(internal)){duplicate=true;break;}
                if(!duplicate) impls.add(new String[]{internal, res.sidecarName});
            }
            pendingConv.put(internal, cv); conv++;
        } catch (Throwable ex) { skipped.add(internal); LateAttachVerifier.error("CONVERSION_FAILURE",internal,"target",rootMsg(ex)); InjectionLogger.error("convert fail "+internal+" -> "+rootMsg(ex)); } }
        if(!phaseFailures.isEmpty()||!skipped.isEmpty()||LateAttachVerifier.hasErrors()){
            LateAttachVerifier.writeReport();
            throw new IllegalStateException("Late-attach conversion gate failed; no partial conversion was published");
        }
        convMap.putAll(pendingConv);ifaceMap.putAll(pendingIfaces);
        // Eager-define only for targets that were already loaded, using that target's exact protection domain.
        for(var e:pendingConv.entrySet()){Class<?> lc=loadedMap.get(e.getKey());if(lc==null)continue;
            if(e.getValue().define(lc.getProtectionDomain()))eager++;}
        if(LateAttachVerifier.hasErrors()){
            LateAttachVerifier.writeReport();
            throw new IllegalStateException("Late-attach sidecar definition gate failed");
        }
        InjectionLogger.info("converted "+conv+" targets ("+eager+" sidecars eager-defined); ifaceMap="+ifaceMap.size()+" interfaces; skipped="+skipped.size()+(skipped.isEmpty()?"":" "+skipped));
        for (var ie : ifaceMap.entrySet()) for (String[] impl : ie.getValue())
            lbrt.DuckDispatch.register(ie.getKey(), impl[0], impl[1]);

        // Resolver: LB access of a non-public member of an ALREADY-LOADED MC class must be reflection-routed (that
        // class could not be AW-widened, since retransform bans modifier changes). Future MC classes get the AW on-load.
        RetransformConverter.Resolver awRes = new RetransformConverter.Resolver(){
            public boolean fieldNeedsReflect(String o,String n,String d){ return INACC.contains(o) || (isPreloadedMc(o) && fieldNonPublic(o,n)); }
            public boolean methodNeedsReflect(String o,String n,String d){ return INACC.contains(o) || (isPreloadedMc(o) && methodNonPublic(o,n,d)); }
            public boolean typeInaccessible(String in){ return INACC.contains(in); }
        };
        // CFT: mixin target loads/retransforms -> lazily define sidecar+state (class PD), return target' (AW-widened if
        // future-loaded). Future non-target MC class -> apply AW on-load. LB class -> caller-rewrite to sidecars + reflect
        // its direct accesses to already-loaded MC members.
        CFT = new ClassFileTransformer(){ public byte[] transform(ClassLoader l,String n,Class<?> c,ProtectionDomain p,byte[] b){
            if (n==null) return null;
            try {
                boolean connect = n.equals(JoinGateRewriter.CONNECT_SCREEN);
                Conv cv = convMap.get(n);
                if (cv!=null) {
                    byte[] t;
                    if (c==null) t = cv.target;
                    else { long s=System.nanoTime(); t = RetransformConverter.rebase(cv.target, b); tRebase += System.nanoTime()-s; nRebase++; }
                    if(connect)t=JoinGateRewriter.rewrite(n,t);
                    if (c!=null) { long s=System.nanoTime(); boolean ok=LateAttachVerifier.verifyRetransform(n, b, t); tVerify += System.nanoTime()-s; if(!ok) return null; }
                    if(!cv.define(p)){
                        // Future-loaded target (c==null) can hit this AFTER the join gate opened; the JVM then defines the
                        // pristine original with NO LB hooks, so the feature backing this target is silently inert for the
                        // session. Surface it to the live injection log (not just the verifier report) so it is observable.
                        InjectionLogger.error("sidecar/state define failed for "+n+" — it will load WITHOUT LiquidBounce hooks (that feature is inert this session; restart the client to retry)");
                        return null;
                    }
                    if (c==null) { byte[] w=awApply(n,t); if(w!=null) t=w; }
                    return t; }
                if (n.startsWith("net/ccbluex/")) {
                    byte[] rw = AccessorBridgeRewriter.rewrite(n, b);
                    rw = ifaceMap.isEmpty()? rw : RetransformConverter.rewriteCaller(rw, ifaceMap);
                    rw = RetransformConverter.rewriteLbAw(rw, awRes);
                    if (!LateAttachVerifier.verifyCaller(n, b, rw, gadded, gfield, ifaceMap)) return null;
                    return rw==b? null : rw;
                }
                if (connect) {
                    byte[] rw=JoinGateRewriter.rewrite(n,b);
                    if(c!=null&&!LateAttachVerifier.verifyRetransform(n,b,rw))return null;
                    if(c==null){byte[] w=awApply(n,rw);if(w!=null)rw=w;}
                    return rw==b?null:rw;
                }
                if (n.startsWith("net/minecraft/")||n.startsWith("com/mojang/")) return PLATFORM.cftAppliesAw()? awApply(n,b) : null;  // widen future MC classes (on-load) + AW-class retransforms (modded loaders widen future classes themselves)
            } catch(Throwable x){ LateAttachVerifier.error("TRANSFORM_FAILURE",n,"cft",rootMsg(x)); InjectionLogger.error("CFT fail "+n, x); }
            return null; } };
        inst.addTransformer(CFT, true);
        everInjected = true;   // committed: LB staged + targets converted + transformer live -> refuse any re-inject
        refreshLoadedAccessState(inst,aw);

        // Force the join entry point through the installed CFT now. A later first-click load is too late to prove that
        // the gate exists before registries are thawed.
        try{
            boolean connectWasLoaded=preLoaded.contains(JoinGateRewriter.CONNECT_SCREEN);
            Class<?> connectClass=Class.forName(JoinGateRewriter.CONNECT_SCREEN.replace('/','.'),false,SYS);
            if(connectWasLoaded){
                if(!inst.isModifiableClass(connectClass))throw new UnmodifiableClassException(connectClass.getName());
                inst.retransformClasses(connectClass);
            }
        }catch(Throwable e){LateAttachVerifier.error("JOIN_GATE_PREFLIGHT_FAILURE",JoinGateRewriter.CONNECT_SCREEN,"join-gate",rootMsg(e));}
        preflightLbClasses(lbBundle,awRes,gadded,gfield,ifaceMap);
        if(LateAttachVerifier.hasFatalErrors()){
            LateAttachVerifier.writeReport();
            throw new IllegalStateException("Late-attach preflight failed before target retransformation; see report");
        }

        for(Class<?> c:inst.getAllLoadedClasses())if(c.getName().startsWith("net.ccbluex."))preBootstrapLb.add(c.getName());
        lbrt.JoinGate.block();

        // Bootstrap LB on the Minecraft thread while target hooks are still INACTIVE. ClientStartEvent launches an
        // asynchronous coroutine; activating targets merely in the same initial turn is not enough, because later
        // render/entity hooks can run before managers/features finish and circularly initialize Kotlin singletons.
        // The readiness waiter below publishes all target retransforms only after LiquidBounce.isInitialized=true.
        try {
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, SYS);
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            Runnable kick = () -> { RegistryThawSession thaw=null; try {
                // Initializing the singleton registers its ClientStartEvent handler. Loading it with initialize=false
                // fires the event into an empty listener set; the readiness waiter then initializes LiquidBounce on
                // its own background thread after the event was already lost, causing module singleton races.
                Class.forName("net.ccbluex.liquidbounce.LiquidBounce", true, SYS);
                Class<?> em = Class.forName("net.ccbluex.liquidbounce.event.EventManager", true, SYS);
                Object emInst = em.getField("INSTANCE").get(null);
                Object ev = Class.forName("net.ccbluex.liquidbounce.event.events.ClientStartEvent", true, SYS).getField("INSTANCE").get(null);
                java.lang.reflect.Method callEvent = em.getMethod("callEvent", Class.forName("net.ccbluex.liquidbounce.event.Event", true, SYS));
                if(LateAttachVerifier.hasFatalErrors())throw new IllegalStateException("Bootstrap class preflight produced verification errors");
                thaw = RegistryThawSession.begin();
                InjectionLogger.info("(MC main thread) callEvent(ClientStartEvent)");
                callEvent.invoke(emInst, ev);
                if(LateAttachVerifier.hasFatalErrors())throw new IllegalStateException("ClientStartEvent class loading produced verification errors");
                InjectionLogger.info("ClientStartEvent dispatched");
                restoreRegistriesAfterInitialization(mcCls, mc, thaw);
            } catch (Throwable t) {
                // Always reopen the gate on ANY kick failure, including a throw BEFORE RegistryThawSession.begin()
                // (LiquidBounce clinit, reflection, or the fatal-error check) where thaw is still null — otherwise
                // the gate blocked above stays closed for the session and multiplayer is permanently locked out.
                if(thaw!=null)thaw.close();
                lbrt.JoinGate.cancelAndOpen();
                LateAttachVerifier.error("BOOTSTRAP_KICK_FAILURE","LiquidBounce","bootstrap",rootMsg(t));
                InjectionLogger.error("kick error", t); } };
            mcCls.getMethod("execute", Runnable.class).invoke(mc, kick);
            InjectionLogger.info("scheduled LB bootstrap on MC main thread");
        } catch (Throwable e) { LateAttachVerifier.error("BOOTSTRAP_SCHEDULE_FAILURE","LiquidBounce","bootstrap",rootMsg(e)); lbrt.JoinGate.cancelAndOpen(); InjectionLogger.error("bootstrap kick failed", e); }
    }

    /** Reverse a prior late-attach in the SAME JVM (re-attach the agent with agentArgs "mode=uninject"). Two steps, both
     *  on the MC main thread in one turn so no frame renders mid-teardown:
     *   1) LiquidBounce's OWN shutdown via ClientShutdownEvent -> EventManager.unregisterAll (all hooks go inert),
     *      ChunkScanner stop, ConfigSystem.storeAll, BrowserBackendManager.stop -> MCEF.shutdown (native CEF torn down).
     *   2) removeTransformer + retransform every loaded mixin target + the connect-screen hook back to ORIGINAL bytes
     *      (retransform is revertible by design), so the injected hooks physically disappear.
     *  Benign, unavoidable residue: the defined sidecar/state classes stay loaded but go dead (unreferenced once targets
     *  revert), and classes AccessWidened on load keep their widened access (retransform cannot narrow modifiers). */
    static void uninject(Instrumentation inst) throws Exception {
        if (CFT == null || PLATFORM == null || SYS == null) { InjectionLogger.info("uninject: no active LiquidBounce injection in this JVM; nothing to do"); return; }
        InjectionLogger.info("=== UNINJECT: LiquidBounce shutdown + bytecode revert ===");
        Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, SYS);
        Object mc = mcCls.getMethod("getInstance").invoke(null);
        CompletableFuture<Throwable> done = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable task = () -> { Throwable err = null;
            if (!claimed.compareAndSet(false, true)) { done.complete(null); return; }   // waiter already gave up -> do NOT revert
            try {
            // 1) LB's own teardown (unregister listeners, stop threads, save config, stop the CEF browser).
            try {
                Class<?> em = Class.forName("net.ccbluex.liquidbounce.event.EventManager", true, SYS);
                Object emInst = em.getField("INSTANCE").get(null);
                Object ev = Class.forName("net.ccbluex.liquidbounce.event.events.ClientShutdownEvent", true, SYS).getField("INSTANCE").get(null);
                em.getMethod("callEvent", Class.forName("net.ccbluex.liquidbounce.event.Event", true, SYS)).invoke(emInst, ev);
                InjectionLogger.info("uninject: ClientShutdownEvent dispatched (listeners unregistered, CEF browser stopped)");
            } catch (Throwable t) { InjectionLogger.warn("uninject: LB shutdown threw, continuing to revert -> " + rootMsg(t)); }
            // 2) Drop our transformer, then revert. Split by when the class loaded:
            //    - preLoaded targets: their target' kept ORIGINAL schema + modifiers, so revert straight to original.
            //    - targets loaded AFTER attach (e.g. ConnectScreen, whose connect/updateStatus are AccessWidened to
            //      public): they were widened on load, and retransform cannot narrow modifiers, so re-derive from
            //      original and re-apply ONLY the AccessWidener (drops the injected hooks, keeps the widened access).
            inst.removeTransformer(CFT);
            List<Class<?>> pureSet = new ArrayList<>(), awSet = new ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) { String in = c.getName().replace('.','/');
                if (!inst.isModifiableClass(c)) continue;
                if (!(convMap.containsKey(in) || in.equals(JoinGateRewriter.CONNECT_SCREEN))) continue;
                (preLoaded.contains(in) ? pureSet : awSet).add(c); }
            int total = pureSet.size() + awSet.size();
            int reverted = revertBatch(inst, pureSet);
            if (!awSet.isEmpty()) {
                java.lang.instrument.ClassFileTransformer awOnly = new java.lang.instrument.ClassFileTransformer(){
                    public byte[] transform(ClassLoader l,String n,Class<?> c,ProtectionDomain p,byte[] b){
                        try { return PLATFORM.applyAw(n, b); } catch (Throwable t) { return null; } } };
                inst.addTransformer(awOnly, true);
                try { reverted += revertBatch(inst, awSet); } finally { inst.removeTransformer(awOnly); }
            }
            InjectionLogger.info("uninject: reverted " + reverted + "/" + total + " classes (injected hooks removed; AccessWidened access retained where widened on load)");
            try { lbrt.JoinGate.cancelAndOpen(); } catch (Throwable ignored) {}   // ensure the join gate is not left blocked
            // 3) If LiquidBounce owned the current Screen (ClickGUI / HUD editor / an error dialog), return to in-game so
            //    the user is not stranded on a now-dead LB screen after its classes go inert.
            try {
                Object screen = mcCls.getField("screen").get(mc);
                if (screen != null && screen.getClass().getName().startsWith("net.ccbluex.")) {
                    Class<?> screenCls = Class.forName("net.minecraft.client.gui.screens.Screen", false, SYS);
                    mcCls.getMethod("setScreen", screenCls).invoke(mc, new Object[]{null});
                    InjectionLogger.info("uninject: cleared LiquidBounce screen -> returned to in-game");
                }
            } catch (Throwable t) { InjectionLogger.warn("uninject: could not clear LB screen -> " + rootMsg(t)); }
            CFT = null;   // teardown ran on the MC thread -> clear here so agent state matches what actually happened
        } catch (Throwable t) { err = t; } finally { done.complete(err); } };
        mcCls.getMethod("execute", Runnable.class).invoke(mc, task);
        Throwable err;
        try { err = done.get(60, TimeUnit.SECONDS); }
        catch (TimeoutException t) {
            if (claimed.compareAndSet(false, true))   // we win the claim -> the queued task will now no-op, so nothing was reverted
                throw new IllegalStateException("uninject timed out waiting for the MC main thread; teardown was NOT performed (retry, or restart the client)");
            // the task already started (it holds the render thread) -> wait for it to finish rather than abandon a half-revert
            try { err = done.get(60, TimeUnit.SECONDS); }
            catch (TimeoutException t2) { throw new IllegalStateException("uninject is still running on the MC main thread after 120s; the client may be wedged"); }
        }
        if (err != null) throw new IllegalStateException("uninject failed on the MC main thread: " + rootMsg(err), err);
        InjectionLogger.info("=== UNINJECT complete; LiquidBounce removed from the running client ===");
    }

    /** Retransform a revert set in ONE VM op; on the all-or-nothing batch failing, isolate per-class so one stubborn
     *  class doesn't block the rest. Returns how many reverted. */
    static int revertBatch(Instrumentation inst, List<Class<?>> cs) {
        if (cs.isEmpty()) return 0;
        try { inst.retransformClasses(cs.toArray(new Class<?>[0])); return cs.size(); }
        catch (Throwable batch) { int ok = 0;
            for (Class<?> c : cs) { try { inst.retransformClasses(c); ok++; }
                catch (Throwable e) { InjectionLogger.warn("uninject: revert fail " + c.getName() + " -> " + rootMsg(e)); } }
            return ok; }
    }

    static long tRebase, tVerify; static int nRebase;   // per-class CFT cost instrumentation (retransform window)

    /** Publish all already-loaded target/caller rewrites from the Minecraft thread after bootstrap readiness. */
    static void activateLoadedTargets(Instrumentation inst) throws Exception {
        long t0=System.nanoTime();
        List<Class<?>> targets=new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) { String in=c.getName().replace('.','/');
            if (convMap.containsKey(in)&&preLoaded.contains(in)&&!in.equals(JoinGateRewriter.CONNECT_SCREEN)&&inst.isModifiableClass(c)) targets.add(c); }
        int rt=batchRetransform(inst, targets, "target");
        long dt=(System.nanoTime()-t0)/1_000_000;
        InjectionLogger.info("retransformed "+rt+"/"+targets.size()+" already-loaded targets on MC main thread ("
            +dt+"ms total; rebase "+(tRebase/1_000_000)+"ms/"+nRebase+" classes, verify "+(tVerify/1_000_000)+"ms)");
        List<Class<?>> callers=new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) if (preBootstrapLb.contains(c.getName())&&inst.isModifiableClass(c)) callers.add(c);
        batchRetransform(inst, callers, "caller");
        LateAttachVerifier.writeReport();
        // Only a FATAL error aborts here; tolerable per-target/caller residue must not force the join gate closed.
        if(LateAttachVerifier.hasFatalErrors())throw new IllegalStateException("Late-attach verification failed during target activation; see report");
    }

    /** Retransform the whole set in ONE VM op (one safepoint, not N). retransformClasses is all-or-nothing, so if a
     *  class legitimately cannot be retransformed live it fails the batch -> we then fall back to per-class ONLY for
     *  that set to isolate the offender while still applying the rest. Common case (all succeed) is a single fast call. */
    static int batchRetransform(Instrumentation inst, List<Class<?>> cs, String kind) {
        if (cs.isEmpty()) return 0;
        try { inst.retransformClasses(cs.toArray(new Class<?>[0])); return cs.size(); }
        catch (Throwable batch) {
            InjectionLogger.warn("batch "+kind+" retransform failed ("+rootMsg(batch)+"); isolating per-class");
            int ok=0;
            for (Class<?> c : cs) { String in=c.getName().replace('.','/');
                try { inst.retransformClasses(c); ok++; }
                catch (Throwable e) { LateAttachVerifier.error(kind.toUpperCase()+"_RETRANSFORM_FAILURE",in,kind,rootMsg(e));
                    InjectionLogger.warn("retransform fail "+in+" -> "+rootMsg(e)); } }
            return ok;
        }
    }
    /** define into the app loader with the given PD (matches signer of signed target packages); true on success or
     *  benign already-defined, false on a real ClassFormat/Verify defect. */
    static boolean define(String dotted, byte[] b, ProtectionDomain pd){ return PLATFORM.defineClass(dotted, b, pd); }
    static synchronized boolean defineSynthetics(byte[] cb, ProtectionDomain pd){
        boolean ok=true;
        for(String in:scanSyn(cb)){
            String d=in.replace('/','.');
            try{
                try{Class.forName(d,false,SYS);definedSynth.add(d);continue;}catch(ClassNotFoundException expected){}
                if(definedSynth.contains(d)||definingSynth.contains(d))continue;
                definingSynth.add(d);boolean one=false;
                try{
                    byte[] sb=PLATFORM.generateClass(d);
                    if(sb==null)syntheticError("SYNTHETIC_GENERATE_FAILURE",d,"Mixin transformer returned no bytes");
                    else if(!defineSynthetics(sb,pd))syntheticError("SYNTHETIC_DEPENDENCY_FAILURE",d,"A generated synthetic dependency failed");
                    else if(!define(d,sb,d.startsWith("org.spongepowered.")?null:pd))syntheticError("SYNTHETIC_DEFINE_FAILURE",d,"Generated class could not be defined");
                    else{definedSynth.add(d);one=true;}
                }finally{definingSynth.remove(d);}
                ok&=one;
            }catch(Throwable t){definingSynth.remove(d);syntheticError("SYNTHETIC_PIPELINE_FAILURE",d,rootMsg(t));ok=false;}
        }
        return ok;
    }
    static void syntheticError(String code,String dotted,String message){
        InjectionLogger.error("SYNTH-FAIL "+dotted+" -> "+message);
        LateAttachVerifier.error(code,dotted.replace('.','/'),"synthetic",message);
    }
    static List<String> scanSyn(byte[] b){ LinkedHashSet<String> o=new LinkedHashSet<>(); try{ ClassReader cr=new ClassReader(b);String self=cr.getClassName(); char[] bu=new char[cr.getMaxStringLength()]; for(int i=1;i<cr.getItemCount();i++){int off=cr.getItem(i); if(off==0||off-1<0)continue; if((b[off-1]&0xff)!=7)continue; try{String n=cr.readUTF8(off,bu); if(n!=null&&!n.equals(self)&&(n.startsWith("org/spongepowered/asm/synthetic/")||n.contains("$Anonymous$")))o.add(n);}catch(Throwable x){}}}catch(Throwable x){} return new ArrayList<>(o); }
    /** Exact, idempotent registry-state transaction. Originally-unfrozen registries stay unfrozen. */
    static final class RegistryThawSession implements AutoCloseable {
        final java.lang.reflect.Field frozen;
        final IdentityHashMap<Object,Boolean> original;
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean restored;
        RegistryThawSession(java.lang.reflect.Field frozen, IdentityHashMap<Object,Boolean> original){this.frozen=frozen;this.original=original;}
        static RegistryThawSession begin() throws Exception {
            Class<?> bir=Class.forName("net.minecraft.core.registries.BuiltInRegistries",false,SYS);
            java.lang.reflect.Field wr=bir.getDeclaredField("WRITABLE_REGISTRY");wr.setAccessible(true);Object root=wr.get(null);
            Class<?> mapped=Class.forName("net.minecraft.core.MappedRegistry",false,SYS);
            java.lang.reflect.Field fz=mapped.getDeclaredField("frozen");fz.setAccessible(true);
            IdentityHashMap<Object,Boolean> states=new IdentityHashMap<>();
            if(mapped.isInstance(root))states.put(root,fz.getBoolean(root));
            for(Object reg:(Iterable<?>)root)if(mapped.isInstance(reg))states.put(reg,fz.getBoolean(reg));
            RegistryThawSession session=new RegistryThawSession(fz,states);
            try{
                for(var e:states.entrySet())if(e.getValue())fz.setBoolean(e.getKey(),false);
                InjectionLogger.info("thawed "+states.values().stream().filter(Boolean::booleanValue).count()+"/"+states.size()+" registries");
                return session;
            }catch(Throwable t){session.close();if(session.restored)try{lbrt.JoinGate.open();}catch(Throwable ignored){}
                if(t instanceof Exception e)throw e;if(t instanceof Error e)throw e;throw new RuntimeException(t);}
        }
        public void close(){if(!closed.compareAndSet(false,true))return;int count=0,failures=0;
            for(var e:original.entrySet())try{frozen.setBoolean(e.getKey(),e.getValue());count++;}
                catch(Throwable t){failures++;LateAttachVerifier.error("REGISTRY_RESTORE_FAILURE","registries","bootstrap",rootMsg(t));}
            restored=failures==0;
            InjectionLogger.info("restored original state of "+count+"/"+original.size()+" registries"+(restored?"":"; join gate remains closed"));}
    }
    static void restoreAndOpen(RegistryThawSession thaw,boolean initializationSucceeded){
        thaw.close();
        // The gate must ALWAYS be released here so a failed/partial bootstrap can never leave multiplayer permanently
        // locked. On full success replay the deferred join; otherwise (registries not fully restored, or LB did not
        // initialize) don't replay into a half-set-up client, but still unblock so the user can retry the join.
        if(thaw.restored && initializationSucceeded){
            try{lbrt.JoinGate.open();}
            catch(Throwable t){LateAttachVerifier.error("DEFERRED_JOIN_FAILURE","ConnectScreen","join-gate",rootMsg(t));lbrt.JoinGate.cancelAndOpen();}
        } else {
            InjectionLogger.error("initialization incomplete; dropping deferred join and unblocking gate");
            lbrt.JoinGate.cancelAndOpen();
        }
    }
    /** LB initializes asynchronously after ClientStartEvent. Restore on every outcome, including timeout/interruption. */
    static void restoreRegistriesAfterInitialization(Class<?> mcCls, Object mc, RegistryThawSession thaw) {
        Thread waiter = new Thread(() -> { java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();try {
            Class<?> lbCls = Class.forName("net.ccbluex.liquidbounce.LiquidBounce", false, SYS);
            Object lb = lbCls.getField("INSTANCE").get(null);
            java.lang.reflect.Method initialized = lbCls.getMethod("isInitialized");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while (!(Boolean) initialized.invoke(lb)) {
                if (System.nanoTime() >= deadline) {
                    LateAttachVerifier.error("BOOTSTRAP_TIMEOUT","registries","bootstrap","Timed out waiting for LiquidBounce initialization");
                    break;
                }
                Thread.sleep(50L);
            }
            if(!LateAttachVerifier.hasFatalErrors()){InjectionLogger.info("LiquidBounce initialization completed");ready.set(true);}
        } catch (InterruptedException t) {
            Thread.currentThread().interrupt();
            LateAttachVerifier.error("BOOTSTRAP_INTERRUPTED","registries","bootstrap",String.valueOf(t));
        } catch (Throwable t) {
            LateAttachVerifier.error("BOOTSTRAP_WAIT_FAILURE","registries","bootstrap",rootMsg(t));
        } finally {
            CompletableFuture<Void> done=new CompletableFuture<>();
            // Exactly one of {main-thread restore, timeout/failure fallback} performs the terminal gate action via the
            // CAS below; the loser only ensures registries are restored (thaw.close is idempotent). This removes the
            // race where both the waiter and a slow-but-live main thread drive the join gate.
            java.util.concurrent.atomic.AtomicBoolean finalized=new java.util.concurrent.atomic.AtomicBoolean();
            Runnable restore=()->{try{
                if(ready.get())try{activateLoadedTargets(INST);}
                catch(Throwable t){ready.set(false);LateAttachVerifier.error("TARGET_ACTIVATION_FAILURE","targets","bootstrap",rootMsg(t));InjectionLogger.error("target activation failed -> "+rootMsg(t));}
                if(finalized.compareAndSet(false,true))restoreAndOpen(thaw,ready.get());else thaw.close();
            }finally{done.complete(null);}};
            try {
                mcCls.getMethod("execute", Runnable.class).invoke(mc, restore);
                // If the main-thread restore runnable (which releases the gate via restoreAndOpen) never completes,
                // release the gate here too so a stalled/failed activation cannot leave multiplayer locked out.
                try{done.get(60,TimeUnit.SECONDS);}
                catch(TimeoutException t){LateAttachVerifier.warn("REGISTRY_RESTORE_MAIN_THREAD_TIMEOUT","registries","bootstrap","Main thread did not finish activation/restore within 60 seconds; applying state fallback");if(finalized.compareAndSet(false,true)){thaw.close();lbrt.JoinGate.cancelAndOpen();}else thaw.close();}
                catch(InterruptedException t){Thread.currentThread().interrupt();if(finalized.compareAndSet(false,true)){thaw.close();lbrt.JoinGate.cancelAndOpen();}else thaw.close();}
                catch(ExecutionException t){LateAttachVerifier.error("REGISTRY_RESTORE_TASK_FAILURE","registries","bootstrap",rootMsg(t));if(finalized.compareAndSet(false,true)){thaw.close();lbrt.JoinGate.cancelAndOpen();}else thaw.close();}
            } catch(Throwable t){ if(finalized.compareAndSet(false,true)){thaw.close();lbrt.JoinGate.cancelAndOpen();}else thaw.close();LateAttachVerifier.error("REGISTRY_RESTORE_SCHEDULE_FAILURE","registries","bootstrap",rootMsg(t)); }
        } }, "lb-registry-restore");
        waiter.setDaemon(true);
        waiter.start();
    }
    static byte[] awApply(String n, byte[] b){ return PLATFORM.applyAw(n, b); }
    /** Point LiquidBounce's data dir (&lt;gameDir&gt;/LiquidBounce) at the injector-supplied path, else the client's REAL
     *  game directory — so it matches a normal install instead of landing in whatever working directory the client was
     *  launched from. Runs before LB's VanillaPlatform reads the vspike.gameDir system property. */
    static void applyDataDir(String a) {
        String dir = null;
        try {
            String override = InjectionLogger.argValue(a, "gameDir");
            if (override == null || override.isBlank()) {
                // default: the client's REAL game directory (matches a normal install)
                Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, SYS);
                Object gd = mcCls.getField("gameDirectory").get(mcCls.getMethod("getInstance").invoke(null));
                if (gd instanceof File file) dir = file.getAbsolutePath();
            } else if (override.equals(".")) {
                dir = System.getProperty("user.dir");      // the client's working directory (CWD)
            } else {
                dir = new File(override).getAbsoluteFile().toPath().normalize().toString();
            }
        } catch (Throwable t) { InjectionLogger.warn("could not resolve game directory ("+rootMsg(t)+"); LiquidBounce falls back to the process working directory"); }
        if (dir != null) {
            System.setProperty("vspike.gameDir", dir);
            InjectionLogger.info("LiquidBounce data directory: " + new File(dir, "LiquidBounce").getAbsolutePath());
        }
    }
    /** Pick the platform by probing which loader owns the live client. Default vanilla; modded selects a stub host. */
    static LoaderPlatform detectPlatform(Instrumentation inst) throws Exception {
        if (loaderPresent(inst,"net.fabricmc.loader.impl.launch.knot.KnotClassLoader")) { InjectionLogger.info("detected Fabric (Knot) loader"); return new FabricPlatform(); }
        if (loaderPresent(inst,"net.neoforged.fml.classloading.transformation.TransformingClassLoader")) { InjectionLogger.info("detected NeoForge (FML) loader"); return new NeoForgePlatform(inst); }
        InjectionLogger.info("detected vanilla (system) loader"); return new VanillaPlatform();
    }
    static boolean loaderPresent(Instrumentation inst, String dotted){ for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(dotted)) return true; return false; }
    static void verifyAsmRuntime() throws Exception {
        Class<?> opcodes=Class.forName("org.objectweb.asm.Opcodes",true,SYS);
        String version=opcodes.getPackage().getImplementationVersion();
        Object source=opcodes.getProtectionDomain().getCodeSource()==null?"bootstrap/unknown":opcodes.getProtectionDomain().getCodeSource().getLocation();
        Class<?> asmInfo=Class.forName("org.spongepowered.asm.util.asm.ASM",true,SYS);
        boolean supported=(Boolean)asmInfo.getMethod("isAtLeastVersion",int.class,int.class).invoke(null,9,8);
        InjectionLogger.info("ASM runtime="+version+" source="+source+" Java25Compatible="+supported);
        if(!supported)throw new IllegalStateException("LiquidBounce Java 25 requires ASM >= 9.8, but active ASM is "+version+" from "+source+". Restart with a correctly packaged agent or remove the preloaded ASM collision.");
    }
    static void preflightLbClasses(Path lbJar,RetransformConverter.Resolver resolver,Map<String,String[]> gadded,
            Map<String,String[]> gfield,Map<String,List<String[]>> ifaces){int total=0,changed=0,failed=0;
        try(JarFile jf=new JarFile(lbJar.toFile())){for(var en=jf.entries();en.hasMoreElements();){JarEntry e=en.nextElement();String n=e.getName();
            if(!n.startsWith("net/ccbluex/")||!n.endsWith(".class"))continue;total++;
            try{byte[] b;try(InputStream in=jf.getInputStream(e)){b=in.readAllBytes();}
                byte[] rw=AccessorBridgeRewriter.rewrite(n.substring(0,n.length()-6),b);
                if(!ifaces.isEmpty())rw=RetransformConverter.rewriteCaller(rw,ifaces);
                rw=RetransformConverter.rewriteLbAw(rw,resolver);
                if(!LateAttachVerifier.verifyCaller(n.substring(0,n.length()-6),b,rw,gadded,gfield,ifaces))failed++;
                if(!Arrays.equals(b,rw))changed++;
            }catch(Throwable t){failed++;LateAttachVerifier.error("CALLER_PREFLIGHT_FAILURE",n,"caller",rootMsg(t));}
        }}catch(Throwable t){LateAttachVerifier.error("CALLER_PREFLIGHT_IO_FAILURE",String.valueOf(lbJar),"caller",rootMsg(t));failed++;}
        InjectionLogger.info("LB caller preflight: "+total+" classes, "+changed+" rewritten, "+failed+" failed");
    }
    static void refreshLoadedAccessState(Instrumentation inst,Object aw){try{
        java.lang.reflect.Field caF=aw.getClass().getDeclaredField("classAccessible");caF.setAccessible(true);
        @SuppressWarnings("unchecked") Set<String> ca=(Set<String>)caF.get(aw);
        int added=0;for(Class<?> c:inst.getAllLoadedClasses()){String n=c.getName().replace('.','/');
            if(!(n.startsWith("net/minecraft/")||n.startsWith("com/mojang/")))continue;
            if(preLoaded.add(n))added++;
            if(ca.contains(n)&&!java.lang.reflect.Modifier.isPublic(c.getModifiers()))INACC.add(n);
        }
        InjectionLogger.info("refreshed attach-window classes: +"+added+"; inaccessible="+INACC.size());
    }catch(Throwable t){LateAttachVerifier.error("ACCESS_STATE_REFRESH_FAILURE","access-widener","preflight",rootMsg(t));}}
    static boolean isPreloadedMc(String o){ return preLoaded.contains(o) && (o.startsWith("net/minecraft/")||o.startsWith("com/mojang/")); }
    static boolean fieldNonPublic(String owner, String name){ return cachedBoolean(npField,owner+"#"+name,() -> {
        try { Class<?> c = Class.forName(owner.replace('/','.'), false, SYS);
            for (Class<?> t=c; t!=null; t=t.getSuperclass()) { for (var f : t.getDeclaredFields()) if (f.getName().equals(name)) return !java.lang.reflect.Modifier.isPublic(f.getModifiers()); }
        } catch(Throwable t){} return false; }); }
    static boolean methodNonPublic(String owner, String name, String desc){ return cachedBoolean(npMethod,owner+"#"+name+" "+desc,() -> {
        try { Class<?> c = Class.forName(owner.replace('/','.'), false, SYS);
            if (name.equals("<init>")) { for (var ctor : c.getDeclaredConstructors()) if (org.objectweb.asm.Type.getConstructorDescriptor(ctor).equals(desc)) return !java.lang.reflect.Modifier.isPublic(ctor.getModifiers()); return false; }
            for (Class<?> t=c; t!=null; t=t.getSuperclass()) { for (var m : t.getDeclaredMethods()) if (m.getName().equals(name) && org.objectweb.asm.Type.getMethodDescriptor(m).equals(desc)) return !java.lang.reflect.Modifier.isPublic(m.getModifiers()); }
        } catch(Throwable t){} return false; }); }
    static boolean cachedBoolean(Map<String,Boolean> cache,String key,java.util.function.BooleanSupplier resolver){
        Boolean cached=cache.get(key);if(cached!=null)return cached;
        boolean resolved=resolver.getAsBoolean();Boolean raced=cache.putIfAbsent(key,resolved);
        return raced==null?resolved:raced;
    }
    static String rootMsg(Throwable t){ while(t.getCause()!=null)t=t.getCause(); return t.getClass().getSimpleName()+": "+t.getMessage(); }
}
