package probeagent;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.service.MixinService;

import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

/**
 * agentmain probe: answers the single gate question for Fabric late-attach.
 *
 * Does calling Fabric's LIVE IMixinTransformer.transformClassBytes(...) on an ALREADY-LOADED
 * target, AFTER Mixins.addConfiguration(...) post-boot, actually apply that late-added config?
 *
 * No ASM in this jar (Fabric aborts on duplicate ASM). Verdict is decided by scanning the
 * returned bytes for the probe handler's marker string / method name in the constant pool.
 */
public class ProbeAgent {

    static OutputStream LOGF;

    static void log(String s) {
        String line = "[FPROBE] " + s;
        System.out.println(line);
        try {
            if (LOGF != null) { LOGF.write((line + "\n").getBytes(StandardCharsets.UTF_8)); LOGF.flush(); }
        } catch (IOException ignored) {}
    }

    public static void agentmain(String args, Instrumentation inst) {
        try {
            Path logPath = Paths.get(System.getProperty("java.io.tmpdir"), "fabricprobe", "probe-verdict.log");
            Files.createDirectories(logPath.getParent());
            LOGF = Files.newOutputStream(logPath);
        } catch (Throwable t) { /* stdout only */ }

        try {
            log("=====================================================================");
            log("agentmain START; args='" + args + "'");

            // 1. Locate the already-loaded Minecraft class and its KnotClassLoader.
            Class<?> mcClass = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().equals("net.minecraft.client.Minecraft")) { mcClass = c; break; }
            }
            if (mcClass == null) {
                log("RED(setup): net.minecraft.client.Minecraft is NOT loaded — cannot test already-loaded case");
                return;
            }
            final ClassLoader knotCL = mcClass.getClassLoader();
            log("Minecraft loaded by: " + knotCL.getClass().getName() + " @" + Integer.toHexString(System.identityHashCode(knotCL)));

            final String probeJar = args == null ? "" : args.trim();
            final Class<?> fmc = mcClass;
            final CountDownLatch latch = new CountDownLatch(1);
            final Runnable task = () -> {
                try { runProbe(knotCL, fmc, probeJar); }
                catch (Throwable t) { log("PROBE EXCEPTION: " + t); t.printStackTrace(); }
                finally { latch.countDown(); }
            };

            // 2. Run on the MC render/main thread via Minecraft.execute(Runnable) — the appropriate
            //    thread for touching the Mixin pipeline. Fall back to the agent thread if needed.
            boolean scheduled = false;
            try {
                Object mc = mcClass.getMethod("getInstance").invoke(null);
                if (mc != null) {
                    Method execute = mcClass.getMethod("execute", Runnable.class);
                    execute.invoke(mc, task);
                    scheduled = true;
                    log("scheduled probe on MC thread via Minecraft.execute()");
                }
            } catch (Throwable t) {
                log("could not schedule on MC thread (" + t + ") — running on agent thread");
            }
            if (!scheduled) task.run();

            if (!latch.await(60, TimeUnit.SECONDS)) log("WARN: probe did not finish within 60s");
            log("agentmain DONE");
        } catch (Throwable t) {
            log("agentmain FATAL: " + t);
            t.printStackTrace();
        } finally {
            try { if (LOGF != null) LOGF.close(); } catch (IOException ignored) {}
        }
    }

    static void runProbe(ClassLoader knotCL, Class<?> mcClass, String probeJar) throws Exception {
        // 3. Put the probe's own mixin + config onto Knot (same identity trap fix as the spike).
        FabricLauncher launcher = FabricLauncherBase.getLauncher();
        if (probeJar.isEmpty()) throw new IllegalStateException("probe jar path not supplied as agentArgs");
        launcher.addToClassPath(Paths.get(probeJar), "probe");
        log("addToClassPath(" + probeJar + ", 'probe') OK; targetCL=" + launcher.getTargetClassLoader().getClass().getName());

        String svc = MixinService.getService().getClass().getName();
        Mixins.addConfiguration("probe.mixins.json");
        log("Mixins.addConfiguration(probe.mixins.json) OK; live service=" + svc);

        // 4. Acquire the LIVE transformer (MixinServiceKnot.getTransformer(), package-private static).
        Class<?> svcKnot = Class.forName("net.fabricmc.loader.impl.launch.knot.MixinServiceKnot");
        Method getTr = svcKnot.getDeclaredMethod("getTransformer");
        getTr.setAccessible(true);
        Object transformer = getTr.invoke(null);
        log("MixinServiceKnot.getTransformer() = " + (transformer == null ? "null" : transformer.getClass().getName()));
        if (transformer == null) { log("RED: live transformer is null"); return; }

        // 5. Read the already-loaded target's byte planes through Knot. The byte methods live on the
        //    KnotClassDelegate (KnotClassLoaderInterface), reached via KnotClassLoader.getDelegate().
        Method getDelegate = knotCL.getClass().getDeclaredMethod("getDelegate");
        getDelegate.setAccessible(true);
        Object delegate = getDelegate.invoke(knotCL);
        log("delegate = " + delegate.getClass().getName());
        byte[] raw = (byte[]) invokeByteMethod(delegate, "getRawClassBytes", "net/minecraft/client/Minecraft");
        byte[] pre = (byte[]) invokeByteMethod(delegate, "getPreMixinClassBytes", "net/minecraft/client/Minecraft");
        log("getRawClassBytes len=" + (raw == null ? "null" : raw.length)
                + " ; getPreMixinClassBytes len=" + (pre == null ? "null" : pre.length));
        if (pre == null) { log("RED(setup): getPreMixinClassBytes returned null"); return; }

        boolean preHasMarker = contains(pre, MARKER);
        boolean preHasHandler = contains(pre, HANDLER);
        log("CONTROL: preMixin.contains(marker)=" + preHasMarker + " preMixin.contains(handlerName)=" + preHasHandler);

        // 6. Direct on-demand transform of the ALREADY-LOADED target with the LATE-added config.
        //    Invoke via the PUBLIC IMixinTransformer interface (the impl class is package-private).
        Class<?> iTransformer = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer");
        Method tcb = iTransformer.getMethod("transformClassBytes", String.class, String.class, byte[].class);
        tcb.setAccessible(true);
        byte[] x = (byte[]) tcb.invoke(transformer, "net.minecraft.client.Minecraft", "net.minecraft.client.Minecraft", pre);
        log("transformClassBytes returned X len=" + (x == null ? "null" : x.length));
        if (x == null) { log("RED: transformClassBytes returned null"); return; }

        // 7. Verdict.
        boolean xHasMarker = contains(x, MARKER);
        boolean xHasHandler = contains(x, HANDLER);
        boolean lenDiff = x.length != pre.length;
        log("EVIDENCE: X.len(" + x.length + ") != preMixin.len(" + pre.length + ") = " + lenDiff);
        log("EVIDENCE: X.contains(markerString)=" + xHasMarker);
        log("EVIDENCE: X.contains(handlerMethodName 'probe$onGetWindow')=" + xHasHandler);

        if ((xHasMarker || xHasHandler) && !preHasMarker) {
            log("################################################################");
            log("## RESULT: GREEN");
            log("## Direct transformClassBytes APPLIED the late-added config to an");
            log("## already-loaded target. Fabric late-attach is viable as designed.");
            log("################################################################");
        } else {
            log("################################################################");
            log("## RESULT: RED");
            log("## Late-added config was NOT applied by the direct transformClassBytes");
            log("## call (probe injection absent from X). See diagnostics above.");
            log("################################################################");
            // extra diagnostics for a RED to guide the workaround investigation
            dumpDiagnostics(transformer);

            // WORKAROUND probe: checkSelect() only re-selects an unvisited (late-added) config when
            // MixinProcessor.transformedCount == 0. At late-attach it's huge. Reset it to 0 so Sponge's
            // own select()/selectConfigs()/prepareConfigs() runs, then re-transform and re-check.
            try {
                java.lang.reflect.Field fProc = transformer.getClass().getDeclaredField("processor");
                fProc.setAccessible(true);
                Object processor = fProc.get(transformer);
                java.lang.reflect.Field fCount = processor.getClass().getDeclaredField("transformedCount");
                fCount.setAccessible(true);
                int before = fCount.getInt(processor);
                fCount.setInt(processor, 0);
                int unvisited = org.spongepowered.asm.mixin.Mixins.getUnvisitedCount();
                log("WORKAROUND: reset MixinProcessor.transformedCount " + before + " -> 0 ; Mixins.getUnvisitedCount()=" + unvisited);
                byte[] x2 = (byte[]) tcb.invoke(transformer, "net.minecraft.client.Minecraft", "net.minecraft.client.Minecraft", pre);
                boolean x2Marker = contains(x2, MARKER);
                boolean x2Handler = contains(x2, HANDLER);
                log("WORKAROUND EVIDENCE: X2 len=" + (x2 == null ? "null" : x2.length)
                        + " contains(marker)=" + x2Marker + " contains(handlerName)=" + x2Handler
                        + " ; unvisitedAfter=" + org.spongepowered.asm.mixin.Mixins.getUnvisitedCount());
                if (x2Marker || x2Handler) {
                    log("## WORKAROUND RESULT: GREEN — forcing config re-selection (transformedCount=0) makes the");
                    log("## late-added config apply on a direct transformClassBytes call. RED is RECOVERABLE.");
                } else {
                    log("## WORKAROUND RESULT: RED — still not applied even after transformedCount reset.");
                }
            } catch (Throwable t) {
                log("WORKAROUND failed: " + t); t.printStackTrace();
            }
        }
    }

    static void dumpDiagnostics(Object transformer) {
        try {
            log("DIAG: transformer class hierarchy: " + transformer.getClass().getName()
                    + " <- " + (transformer.getClass().getSuperclass() == null ? "?" : transformer.getClass().getSuperclass().getName()));
            // Try to enumerate registered configs via Mixins.getConfigs()
            try {
                java.util.Set<?> cfgs = org.spongepowered.asm.mixin.Mixins.getConfigs();
                StringBuilder sb = new StringBuilder();
                for (Object c : cfgs) sb.append(c.toString()).append(" | ");
                log("DIAG: Mixins.getConfigs() (" + cfgs.size() + "): " + sb);
            } catch (Throwable t) { log("DIAG: Mixins.getConfigs() failed: " + t); }
        } catch (Throwable t) { log("DIAG failed: " + t); }
    }

    /** Reflectively invoke a (String)->byte[] method declared on the class or any of its interfaces/supers. */
    static Object invokeByteMethod(Object target, String name, String arg) throws Exception {
        Class<?> c = target.getClass();
        Method m = null;
        for (Class<?> k = c; k != null && m == null; k = k.getSuperclass()) {
            m = findDeclared(k, name);
            for (Class<?> ifc : k.getInterfaces()) { if (m != null) break; m = findDeclared(ifc, name); }
        }
        if (m == null) throw new NoSuchMethodException(name + " not found on " + c.getName());
        m.setAccessible(true);
        return m.invoke(target, arg);
    }
    static Method findDeclared(Class<?> k, String name) {
        try { return k.getDeclaredMethod(name, String.class); } catch (NoSuchMethodException e) { return null; }
    }

    static final String MARKER = "[FPROBE] >>> PROBE MIXIN HANDLER";
    static final String HANDLER = "probe$onGetWindow";

    static boolean contains(byte[] hay, String needleStr) {
        if (hay == null) return false;
        byte[] needle = needleStr.getBytes(StandardCharsets.UTF_8);
        if (needle.length == 0 || hay.length < needle.length) return false;
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
