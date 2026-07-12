import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.CommandLineOptions;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Side;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;
import org.objectweb.asm.ClassReader;
import java.io.*; import java.lang.instrument.*; import java.lang.reflect.Method;
import java.nio.file.*; import java.security.ProtectionDomain; import java.util.*; import java.util.concurrent.*; import java.util.jar.*;
import vspike.VSpikeService;

/** MILESTONE 2/3: attach to a fully-running MC and inject FULL LiquidBounce. Uniform-convert every mixin
 *  target (already-loaded -> retransform; future -> on-load CFT returns target'); rewrite LB callers to
 *  sidecars; then manually kick LB's ClientStartEvent so it initializes on the already-running game. */
public class FullInjectAgent {
    static final ClassLoader SYS = ClassLoader.getSystemClassLoader();
    static IMixinTransformer tr; static MixinEnvironment env; static Method defineClass5;
    static final Set<String> definedSynth = ConcurrentHashMap.newKeySet();
    static final Map<String,Conv> convMap = new ConcurrentHashMap<>();             // internal -> conversion holder
    static final Map<String,String[]> ifaceMap = new HashMap<>();                  // iface -> [target, sidecar]
    static final Set<String> targetSet = new HashSet<>();                          // internal names of mixin targets
    static Instrumentation INST;
    static final Set<String> preLoaded = ConcurrentHashMap.newKeySet();            // MC classes loaded at attach (can't be AW-widened)
    static Object AW; static Method AW_APPLY;                                      // AccessWidener + apply(name,bytes)
    static final Map<String,Boolean> npField = new ConcurrentHashMap<>();          // owner#name -> non-public?
    static final Map<String,Boolean> npMethod = new ConcurrentHashMap<>();         // owner#name desc -> non-public?
    static final Set<String> INACC = ConcurrentHashMap.newKeySet();                // already-loaded package-private MC classes LB references by type
    /** per-target conversion: target' bytes + sidecar/state (defined LAZILY in the CFT with the target's real PD). */
    static final class Conv { byte[] target, sidecar, state; String sidecarName, stateName; volatile boolean defined;
        byte[] define(ProtectionDomain pd){ synchronized(this){ if(defined) return target;
            boolean ok = defineSynthetics(target, pd) & defineSynthetics(sidecar, pd)
                       & FullInjectAgent.define(stateName.replace('/','.'), state, pd)
                       & FullInjectAgent.define(sidecarName.replace('/','.'), sidecar, pd);
            defined = true; if(!ok) System.out.println("[FULL] LATE define issue for "+sidecarName); return target; } } }

    public static void agentmain(String a, Instrumentation inst) throws Exception {
        INST = inst;
        for (Class<?> c : inst.getAllLoadedClasses()) preLoaded.add(c.getName().replace('.','/'));   // snapshot BEFORE we load anything
        inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(), Map.of("java.lang", Set.of(FullInjectAgent.class.getModule())), Set.of(), Map.of());
        defineClass5 = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class); defineClass5.setAccessible(true);
        RetransformConverter.CLASS_BYTES = (nm) -> { try (InputStream in = SYS.getResourceAsStream(nm + ".class")) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; } };
        System.out.println("[FULL] staging LB bundle onto system loader ("+preLoaded.size()+" classes already loaded)");
        File self = new File(FullInjectAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path tmp = Files.createTempDirectory("lb-full-"); tmp.toFile().deleteOnExit();
        try (JarFile jf = new JarFile(self)) { for (var en = jf.entries(); en.hasMoreElements();) { JarEntry e = en.nextElement(); String n = e.getName();
            if (n.equals("lbrt/AwReflect.class")) { byte[] b; try (InputStream in=jf.getInputStream(e)){ b=in.readAllBytes(); } define("lbrt.AwReflect", b, null); continue; }
            if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue; Path o = tmp.resolve(new File(n).getName());
            try (InputStream in = jf.getInputStream(e)) { Files.copy(in, o, StandardCopyOption.REPLACE_EXISTING); } inst.appendToSystemClassLoaderSearch(new JarFile(o.toFile())); } }
        Thread.currentThread().setContextClassLoader(SYS);
        try { Class.forName("vspike.McefNative").getMethod("stageIfBundled", File.class, String.class).invoke(null, self, "[FULL]"); } catch (Throwable t) {}

        System.setProperty("mixin.bootstrapService", "vspike.VSpikeServiceBootstrap"); System.setProperty("mixin.service", "vspike.VSpikeService");
        Object aw = Class.forName("vspike.AccessWidener").getConstructor(InputStream.class).newInstance(SYS.getResourceAsStream("liquidbounce.accesswidener"));
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
        AW = aw; AW_APPLY = aw.getClass().getMethod("apply", String.class, byte[].class);
        // Inaccessible types: AW-widened classes that are ALREADY loaded (so can't be widened) and still non-public.
        // LB references these by type; the CFT erases those references to Object + reflection.
        try { var caF = aw.getClass().getDeclaredField("classAccessible"); caF.setAccessible(true);
            @SuppressWarnings("unchecked") Set<String> ca = (Set<String>) caF.get(aw);
            for (String cn : ca) { if (!preLoaded.contains(cn)) continue; try { Class<?> k = Class.forName(cn.replace('/','.'), false, SYS); if (!java.lang.reflect.Modifier.isPublic(k.getModifiers())) INACC.add(cn); } catch(Throwable t){} }
            System.out.println("[FULL] inaccessible already-loaded AW classes: "+INACC.size()+" "+INACC);
        } catch(Throwable t){ System.out.println("[FULL] INACC compute failed -> "+rootMsg(t)); }

        List<String> targets = new ArrayList<>();
        try (var r = new BufferedReader(new InputStreamReader(SYS.getResourceAsStream("lb-mixin-targets.txt")))) { String l; while ((l=r.readLine())!=null) if(!l.isBlank()) targets.add(l.trim().replace('.','/')); }
        targetSet.addAll(targets);

        // pass 1: convert every target we can read bytes for -> cache target' + build ifaceMap + define sidecars/synthetics.
        // ROBUST: a target is only accepted (put into convertedTarget) if its state+sidecar+synthetics all DEFINE cleanly;
        // otherwise it is skipped (original class left intact) so one bad conversion degrades to "that mixin absent",
        // never a game crash from a target' that calls a sidecar that failed to define.
        Map<String,Class<?>> loadedMap = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) loadedMap.put(c.getName().replace('.','/'), c);
        // [BUG22-DIAG] was GuiMessage already loaded when we attached? (suspected intermittency factor)
        System.out.println("[DBG22] GuiMessage loadedAtAttach="+loadedMap.containsKey("net/minecraft/client/multiplayer/chat/GuiMessage")
                +" preLoaded="+preLoaded.contains("net/minecraft/client/multiplayer/chat/GuiMessage"));
        ProtectionDomain mcPD = null; try { mcPD = Class.forName("net.minecraft.client.Minecraft", false, SYS).getProtectionDomain(); } catch(Throwable t){}
        final ProtectionDomain refPD = mcPD;
        // Phase A: mixin-transform every target and collect the GLOBAL added-method table (so cross-target / base-class
        // @Unique calls route to the right sidecar during conversion).
        LinkedHashMap<String,byte[][]> txMap = new LinkedHashMap<>();
        Map<String,String[]> gadded = new HashMap<>(); Map<String,String[]> gfield = new HashMap<>(); Map<String,String[]> giface = new HashMap<>();
        for (String internal : targets) { try {
            byte[] O; try (InputStream in=SYS.getResourceAsStream(internal+".class")){ if(in==null) continue; O=in.readAllBytes(); }
            byte[] X = tr.transformClassBytes(internal.replace('/','.'), internal.replace('/','.'), O); if (X==null || Arrays.equals(X,O)) continue;
            txMap.put(internal, new byte[][]{O, X}); RetransformConverter.collectAdded(internal, O, X, gadded, gfield, giface);
        } catch (Throwable e) { System.out.println("[FULL] transform skip "+internal+" -> "+rootMsg(e)); } }
        RetransformConverter.GADDED = gadded; RetransformConverter.GFIELD = gfield; RetransformConverter.GIFACE = giface;
        System.out.println("[FULL] phase A: "+txMap.size()+" transformed; global added-methods="+gadded.size()+" added-fields="+gfield.size());
        // Phase B: convert each (rewriteRefs now consults GADDED) + eager-define already-loaded sidecars.
        int conv=0, eager=0; List<String> skipped=new ArrayList<>();
        for (var e : txMap.entrySet()) { String internal = e.getKey(); try {
            byte[] O = e.getValue()[0], X = e.getValue()[1];
            RetransformConverter c = new RetransformConverter(internal); RetransformConverter.Result res = c.run(O, X);
            Conv cv = new Conv(); cv.target=res.target; cv.sidecar=res.sidecar; cv.state=c.stateBytes(); cv.sidecarName=res.sidecarName; cv.stateName=c.stateName();
            for (String di : res.droppedInterfaces) ifaceMap.put(di, new String[]{internal, res.sidecarName});
            convMap.put(internal, cv); conv++;
            Class<?> lc = loadedMap.get(internal);
            ProtectionDomain pd = lc != null ? lc.getProtectionDomain() : refPD;
            if (pd != null) { cv.define(pd); eager++; }
        } catch (Throwable ex) { skipped.add(internal); System.out.println("[FULL] convert skip "+internal+" -> "+rootMsg(ex)); } }
        System.out.println("[FULL] converted "+conv+" targets ("+eager+" sidecars eager-defined); ifaceMap="+ifaceMap.size()+" interfaces; skipped="+skipped.size()+(skipped.isEmpty()?"":" "+skipped));

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
        inst.addTransformer(new ClassFileTransformer(){ public byte[] transform(ClassLoader l,String n,Class<?> c,ProtectionDomain p,byte[] b){
            if (n==null) return null;
            try {
                // [BUG22-DIAG] log EVERY CFT sighting of GuiMessage (even if unconverted) with input byte identity
                boolean g22 = n.equals("net/minecraft/client/multiplayer/chat/GuiMessage");
                if (g22) System.out.println("[DBG22] CFT-in GuiMessage retransform="+(c!=null)+" conv="+convMap.containsKey(n)
                        +" thread="+Thread.currentThread().getName()+" inBytes="+(b==null?-1:b.length)+" inSha="+sha(b)+" inFields="+fieldsOf(b));
                Conv cv = convMap.get(n);
                if (cv!=null) { byte[] t = cv.define(p); boolean awch=false; if (c==null) { byte[] w=awApply(n,t); if(w!=null){ awch=true; t=w; } }
                    if (g22) { System.out.println("[DBG22] CFT-out GuiMessage retransform="+(c!=null)+" aw="+awch+" outBytes="+t.length+" outSha="+sha(t)+" outFields="+fieldsOf(t));
                        try { java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "guimsg-cft-"+(c!=null?"rt":"load")+"-"+System.nanoTime()+".class"), t); } catch(Throwable x){ System.out.println("[DBG22] dump fail "+x); } }
                    return t; }
                if (n.startsWith("net/ccbluex/")) {
                    byte[] rw = n.equals("net/ccbluex/liquidbounce/utils/client/ChatHudExtensionKt")
                        ? rewriteChatHudMessageSetters(b) : b;
                    rw = ifaceMap.isEmpty()? rw : RetransformConverter.rewriteCaller(rw, ifaceMap);
                    rw = RetransformConverter.rewriteLbAw(rw, awRes);
                    if (n.equals("net/ccbluex/liquidbounce/injection/mixins/minecraft/gui/MixinHudAccessor"))
                        rw = rewriteHudStaticAccessors(rw);
                    return rw==b? null : rw;
                }
                if (n.startsWith("net/minecraft/")||n.startsWith("com/mojang/")) return awApply(n,b);  // widen future MC classes (on-load) + AW-class retransforms
            } catch(Throwable x){ System.out.println("[FULL] CFT fail "+n+" -> "+rootMsg(x)); }
            return null; } }, true);

        // retransform already-loaded mixin targets with their converted form (CFT defines their sidecar with the class PD)
        int rt=0; for (Class<?> c : inst.getAllLoadedClasses()) { String in=c.getName().replace('.','/'); if (convMap.containsKey(in)) { try { inst.retransformClasses(c); rt++; } catch(Throwable e){ String det = e.getMessage(); Throwable cc=e; while(cc.getCause()!=null){cc=cc.getCause(); if(cc.getMessage()!=null) det=cc.getMessage();} System.out.println("[FULL] retransform fail "+in+" -> "+e.getClass().getSimpleName()+": "+(det==null?"":det.replace('\n',' ').substring(0,Math.min(det.length(),600)))); } } }
        System.out.println("[FULL] retransformed "+rt+" already-loaded targets");
        // also retransform already-loaded LB classes so their casts are rewritten
        for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().startsWith("net.ccbluex.")) { try { inst.retransformClasses(c); } catch(Throwable e){} }

        // kick LB bootstrap: fire ClientStartEvent (MixinMinecraft's <init> hook already passed). LB's handler does
        // render-thread work (Window.getRefreshRate, MCEF/GL init) so it MUST run on the MC main thread, not this
        // Attach Listener thread -> schedule via Minecraft.execute(Runnable).
        try {
            Class.forName("net.ccbluex.liquidbounce.LiquidBounce", false, SYS);
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, SYS);
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            Class<?> em = Class.forName("net.ccbluex.liquidbounce.event.EventManager");
            Object emInst = em.getField("INSTANCE").get(null);
            Object ev = Class.forName("net.ccbluex.liquidbounce.event.events.ClientStartEvent").getField("INSTANCE").get(null);
            java.lang.reflect.Method callEvent = em.getMethod("callEvent", Class.forName("net.ccbluex.liquidbounce.event.Event"));
            Runnable kick = () -> { try {
                unfreezeRegistries();
                System.out.println("[FULL] (MC main thread) callEvent(ClientStartEvent)");
                callEvent.invoke(emInst, ev);
                System.out.println("[FULL] ClientStartEvent dispatched");
                refreezeRegistriesAfterInitialization(mcCls, mc);
            } catch (Throwable t) { System.out.println("[FULL] kick error -> "+rootMsg(t)); t.printStackTrace(); } };
            mcCls.getMethod("execute", Runnable.class).invoke(mc, kick);
            System.out.println("[FULL] scheduled LB bootstrap on MC main thread");
        } catch (Throwable e) { System.out.println("[FULL] bootstrap kick FAILED -> "+e); e.printStackTrace(); }
    }
    /** define into the app loader with the given PD (matches signer of signed target packages); true on success or
     *  benign already-defined, false on a real ClassFormat/Verify defect. */
    static boolean define(String dotted, byte[] b, ProtectionDomain pd){ try { defineClass5.invoke(SYS, dotted, b, 0, b.length, pd); return true; }
        catch(Throwable t){ Throwable c=t.getCause()!=null?t.getCause():t;
            String m=String.valueOf(c.getMessage());
            if (m.contains("duplicate")) return true;                         // already defined -> fine
            System.out.println("[FULL] DEFINE-FAIL "+dotted+" -> "+c.getClass().getSimpleName()+": "+c.getMessage());
            return false; } }
    static boolean defineSynthetics(byte[] cb, ProtectionDomain pd){ boolean ok=true; for (String in : scanSyn(cb)){ String d=in.replace('/','.'); if(!definedSynth.add(d)) continue; try { try { Class.forName(d,false,SYS); continue;}catch(ClassNotFoundException x){} byte[] sb=tr.generateClass(env,d); if(sb==null){ ok=false; continue; } defineSynthetics(sb,pd); ok &= define(d, sb, d.startsWith("org.spongepowered.")?null:pd);}catch(Throwable t){ ok=false; } } return ok; }
    static List<String> scanSyn(byte[] b){ LinkedHashSet<String> o=new LinkedHashSet<>(); try{ ClassReader cr=new ClassReader(b); char[] bu=new char[cr.getMaxStringLength()]; for(int i=1;i<cr.getItemCount();i++){int off=cr.getItem(i); if(off==0||off-1<0)continue; if((b[off-1]&0xff)!=7)continue; try{String n=cr.readUTF8(off,bu); if(n!=null&&(n.startsWith("org/spongepowered/asm/synthetic/")||n.contains("$Anonymous$")))o.add(n);}catch(Throwable x){}}}catch(Throwable x){} return new ArrayList<>(o); }
    /** Late attach happens long after MC froze its registries; LB registers custom entries (sounds, etc.) at feature
     *  init. Reflectively clear MappedRegistry.frozen on every registry so those registrations succeed. */
    static void unfreezeRegistries(){ try {
        Class<?> bir = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, SYS);
        java.lang.reflect.Field wr = bir.getDeclaredField("WRITABLE_REGISTRY"); wr.setAccessible(true); Object root = wr.get(null);
        Class<?> mapped = Class.forName("net.minecraft.core.MappedRegistry", false, SYS);
        java.lang.reflect.Field fz = mapped.getDeclaredField("frozen"); fz.setAccessible(true);
        int n=0; if (mapped.isInstance(root)) { fz.setBoolean(root, false); n++; }
        for (Object reg : (Iterable<?>) root) if (mapped.isInstance(reg)) { fz.setBoolean(reg, false); n++; }
        System.out.println("[FULL] unfroze "+n+" registries");
    } catch(Throwable t){ System.out.println("[FULL] unfreeze registries failed -> "+rootMsg(t)); } }
    /** LB initializes asynchronously after ClientStartEvent. Keep registries writable only for that window, then
     *  restore the vanilla frozen state before any server/world load calls MappedRegistry.freeze() again. */
    static void refreezeRegistriesAfterInitialization(Class<?> mcCls, Object mc) {
        Thread waiter = new Thread(() -> { try {
            Class<?> lbCls = Class.forName("net.ccbluex.liquidbounce.LiquidBounce", false, SYS);
            Object lb = lbCls.getField("INSTANCE").get(null);
            java.lang.reflect.Method initialized = lbCls.getMethod("isInitialized");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while (!(Boolean) initialized.invoke(lb)) {
                if (System.nanoTime() >= deadline) {
                    System.out.println("[FULL] registry re-freeze timed out waiting for LiquidBounce initialization");
                    return;
                }
                Thread.sleep(50L);
            }
            Runnable freeze = () -> refreezeRegistries();
            mcCls.getMethod("execute", Runnable.class).invoke(mc, freeze);
        } catch (Throwable t) { System.out.println("[FULL] registry re-freeze scheduling failed -> "+rootMsg(t)); } }, "lb-registry-refreeze");
        waiter.setDaemon(true);
        waiter.start();
    }
    static void refreezeRegistries(){ try {
        Class<?> bir = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, SYS);
        java.lang.reflect.Field wr = bir.getDeclaredField("WRITABLE_REGISTRY"); wr.setAccessible(true); Object root = wr.get(null);
        Class<?> mapped = Class.forName("net.minecraft.core.MappedRegistry", false, SYS);
        java.lang.reflect.Field fz = mapped.getDeclaredField("frozen"); fz.setAccessible(true);
        int n=0; if (mapped.isInstance(root)) { fz.setBoolean(root, true); n++; }
        for (Object reg : (Iterable<?>) root) if (mapped.isInstance(reg)) { fz.setBoolean(reg, true); n++; }
        System.out.println("[FULL] re-froze "+n+" registries after LiquidBounce initialization");
    } catch(Throwable t){ System.out.println("[FULL] re-freeze registries failed -> "+rootMsg(t)); } }
    static byte[] awApply(String n, byte[] b){ try { Object r = AW_APPLY.invoke(AW, n, b); return (byte[]) r; } catch(Throwable t){ return null; } }
    static boolean isPreloadedMc(String o){ return preLoaded.contains(o) && (o.startsWith("net/minecraft/")||o.startsWith("com/mojang/")); }
    static boolean fieldNonPublic(String owner, String name){ return npField.computeIfAbsent(owner+"#"+name, k -> {
        try { Class<?> c = Class.forName(owner.replace('/','.'), false, SYS);
            for (Class<?> t=c; t!=null; t=t.getSuperclass()) { for (var f : t.getDeclaredFields()) if (f.getName().equals(name)) return !java.lang.reflect.Modifier.isPublic(f.getModifiers()); }
        } catch(Throwable t){} return false; }); }
    static boolean methodNonPublic(String owner, String name, String desc){ return npMethod.computeIfAbsent(owner+"#"+name+" "+desc, k -> {
        try { Class<?> c = Class.forName(owner.replace('/','.'), false, SYS);
            if (name.equals("<init>")) { for (var ctor : c.getDeclaredConstructors()) if (org.objectweb.asm.Type.getConstructorDescriptor(ctor).equals(desc)) return !java.lang.reflect.Modifier.isPublic(ctor.getModifiers()); return false; }
            for (Class<?> t=c; t!=null; t=t.getSuperclass()) { for (var m : t.getDeclaredMethods()) if (m.getName().equals(name) && org.objectweb.asm.Type.getMethodDescriptor(m).equals(desc)) return !java.lang.reflect.Modifier.isPublic(m.getModifiers()); }
        } catch(Throwable t){} return false; }); }
    /** Static @Accessor methods live on the accessor mixin itself. The standalone late-attach pipeline transforms
     *  target classes manually, so these two callable stubs are not rewritten by Mixin and would retain their
     *  AssertionError bodies. Replace them with cached reflective reads of Hud's already-loaded private statics. */
    static byte[] rewriteHudStaticAccessors(byte[] b) { try {
        org.objectweb.asm.tree.ClassNode c = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(b).accept(c, 0);
        boolean changed = false;
        for (org.objectweb.asm.tree.MethodNode m : c.methods) {
            String field;
            if (m.name.equals("getHotbarSelectionTexture") && m.desc.equals("()Lnet/minecraft/resources/Identifier;"))
                field = "HOTBAR_SELECTION_SPRITE";
            else if (m.name.equals("getScoreboardEntryComparator") && m.desc.equals("()Ljava/util/Comparator;"))
                field = "SCORE_DISPLAY_ORDER";
            else continue;
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            if (m.localVariables != null) m.localVariables.clear();
            m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ACONST_NULL));
            m.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("net/minecraft/client/gui/Hud"));
            m.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(field));
            m.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                "lbrt/AwReflect", "gO", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false));
            org.objectweb.asm.Type rt = org.objectweb.asm.Type.getReturnType(m.desc);
            m.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST, rt.getInternalName()));
            m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
            changed = true;
        }
        if (!changed) return b;
        org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(new org.objectweb.asm.ClassReader(b),
            org.objectweb.asm.ClassWriter.COMPUTE_MAXS | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
            protected String getCommonSuperClass(String a, String bb) { return "java/lang/Object"; }
        };
        c.accept(w);
        System.out.println("[FULL] rewrote static Hud accessor stubs");
        return w.toByteArray();
    } catch (Throwable t) { System.out.println("[FULL] Hud accessor rewrite failed -> "+rootMsg(t)); return b; } }
    /** GuiMessageLineAddition is mixed into both GuiMessage and GuiMessage.Line. The generic dropped-interface map
     *  has one entry per interface and cannot choose the receiver target for ChatHudExtension's new GuiMessage.
     *  Resolve its metadata setters explicitly before the generic caller rewrite. */
    static byte[] rewriteChatHudMessageSetters(byte[] b) { try {
        org.objectweb.asm.tree.ClassNode c = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(b).accept(c, 0);
        boolean changed = false;
        for (org.objectweb.asm.tree.MethodNode m : c.methods) for (org.objectweb.asm.tree.AbstractInsnNode p = m.instructions == null ? null : m.instructions.getFirst(); p != null; p = p.getNext()) {
            if (!(p instanceof org.objectweb.asm.tree.MethodInsnNode mi) || mi.getOpcode() != org.objectweb.asm.Opcodes.INVOKEINTERFACE) continue;
            boolean setId = mi.owner.equals("net/ccbluex/liquidbounce/interfaces/GuiMessageLineAddition")
                && mi.name.equals("liquid_bounce$setId") && mi.desc.equals("(Ljava/lang/String;)V");
            boolean setCount = mi.owner.equals("net/ccbluex/liquidbounce/interfaces/GuiMessageAddition")
                && mi.name.equals("liquid_bounce$setCount") && mi.desc.equals("(I)V");
            if (!setId && !setCount) continue;
            for (org.objectweb.asm.tree.AbstractInsnNode q = p.getPrevious(); q != null; q = q.getPrevious()) {
                if (q instanceof org.objectweb.asm.tree.TypeInsnNode ti && ti.getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST) {
                    ti.desc = "net/minecraft/client/multiplayer/chat/GuiMessage";
                    break;
                }
            }
            String desc = setId
                ? "(Lnet/minecraft/client/multiplayer/chat/GuiMessage;Ljava/lang/String;)V"
                : "(Lnet/minecraft/client/multiplayer/chat/GuiMessage;I)V";
            m.instructions.set(mi, new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                "net/minecraft/client/multiplayer/chat/GuiMessage$$LBSidecar", "h$" + mi.name, desc, false));
            changed = true;
        }
        if (!changed) return b;
        org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(new org.objectweb.asm.ClassReader(b),
            org.objectweb.asm.ClassWriter.COMPUTE_MAXS | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
            protected String getCommonSuperClass(String a, String bb) { return "java/lang/Object"; }
        };
        c.accept(w);
        System.out.println("[FULL] rewrote ChatHud GuiMessage metadata setters");
        return w.toByteArray();
    } catch (Throwable t) { System.out.println("[FULL] ChatHud setter rewrite failed -> "+rootMsg(t)); return b; } }
    static String fieldsOf(byte[] b){ try { org.objectweb.asm.tree.ClassNode c=new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(b).accept(c,0); StringBuilder s=new StringBuilder(); for(var f:c.fields) s.append(f.name).append(" "); return s.toString(); } catch(Throwable t){ return "ERR"; } }
    /** [BUG22-DIAG] short sha-256 of class bytes for identity comparison across CFT events / dumps. */
    static String sha(byte[] b){ try { if(b==null) return "null"; var md=java.security.MessageDigest.getInstance("SHA-256"); byte[] h=md.digest(b); StringBuilder s=new StringBuilder(); for(int i=0;i<6;i++) s.append(String.format("%02x",h[i])); return s.toString(); } catch(Throwable t){ return "ERR"; } }
    static String rootMsg(Throwable t){ while(t.getCause()!=null)t=t.getCause(); return t.getClass().getSimpleName()+": "+t.getMessage(); }
}
